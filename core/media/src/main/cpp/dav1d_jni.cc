// Puente JNI entre Media3 (Dav1dDecoder.kt) y dav1d, el decodificador AV1 de VideoLAN (VLC/Kodi/ffmpeg).
// Se compila fuera de Gradle con _rebuild_av1.sh → core/media/src/main/jniLibs/<abi>/libdav1dJNI.so
//
// Flujo: el hilo del decoder de Media3 llama a nativeDecode (envía el paquete AV1) y nativeDequeue
// (recoge una imagen si hay). dav1d decodifica varios fotogramas en paralelo (frame threading), así que
// las imágenes salen con unos fotogramas de retraso: cada una conserva su propio timestamp.
// La imagen queda "aparcada" en un slot hasta que el renderer la pinta (nativeRender, hilo de
// reproducción) y la libera (nativeRelease).
//
// Pintado: ANativeWindow en YV12 (8 bits). El AV1 de 10 bits (lo normal en anime/BD) se convierte a
// 8 bits al copiar: sin HDR, igual que hacía libgav1. 12 bits no se admite.
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dav1d/dav1d.h>
#include <dlfcn.h>

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>

#define LOG_TAG "dav1dJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define FUNC(RT, NAME, ...)                                                   \
  extern "C" JNIEXPORT RT JNICALL                                             \
      Java_com_shieldstream_tv_media_av1_Dav1dDecoder_##NAME(JNIEnv* env,     \
                                                            jobject /*thiz*/, \
                                                            ##__VA_ARGS__)

namespace {

constexpr int kImageFormatYV12 = 0x32315659;
constexpr int kMaxSlots = 32;
// ADataSpace (API 28+, ANativeWindow_setBuffersDataSpace se busca con dlsym).
constexpr int32_t kStandardBt601 = 2 << 16;
constexpr int32_t kStandardBt709 = 1 << 16;
constexpr int32_t kStandardBt2020 = 6 << 16;
constexpr int32_t kTransferSmpte170m = 3 << 22;
constexpr int32_t kTransferSt2084 = 7 << 22;  // PQ (HDR10 / Dolby Vision)
constexpr int32_t kTransferHlg = 8 << 22;
constexpr int32_t kRangeFull = 1 << 27;
constexpr int32_t kRangeLimited = 2 << 27;

// Espacio de color del fotograma. Con HDR (PQ/HLG) la imagen sale en 8 bits pero etiquetada como
// BT.2020 PQ/HLG: Android la convierte a SDR (tone mapping) en vez de mostrarla lavada.
int32_t DataSpaceFor(const Dav1dPicture& p) {
  int32_t standard = p.p.h >= 720 ? kStandardBt709 : kStandardBt601;
  int32_t transfer = kTransferSmpte170m;
  int32_t range = kRangeLimited;
  if (const Dav1dSequenceHeader* s = p.seq_hdr) {
    if (s->pri == DAV1D_COLOR_PRI_BT2020 || s->mtrx == DAV1D_MC_BT2020_NCL) standard = kStandardBt2020;
    else if (s->mtrx == DAV1D_MC_BT709) standard = kStandardBt709;
    else if (s->mtrx == DAV1D_MC_BT601 || s->mtrx == DAV1D_MC_BT470BG) standard = kStandardBt601;
    if (s->trc == DAV1D_TRC_SMPTE2084) transfer = kTransferSt2084;
    else if (s->trc == DAV1D_TRC_HLG) transfer = kTransferHlg;
    if (s->color_range) range = kRangeFull;
  }
  return standard | transfer | range;
}

using SetDataSpaceFn = int32_t (*)(ANativeWindow*, int32_t);

struct Context {
  Dav1dContext* dav1d = nullptr;
  std::deque<Dav1dPicture> ready;  // Solo hilo del decoder.

  std::mutex slot_mutex;  // Slots: decoder (dequeue) + renderer (render/release).
  Dav1dPicture slots[kMaxSlots];
  bool used[kMaxSlots] = {};

  // Solo hilo de reproducción (render).
  ANativeWindow* window = nullptr;
  jobject surface = nullptr;  // Referencia global.
  int window_width = 0;
  int window_height = 0;
  int32_t data_space = 0;
  SetDataSpaceFn set_data_space = nullptr;
};

void DropReady(Context* ctx) {
  for (auto& p : ctx->ready) dav1d_picture_unref(&p);
  ctx->ready.clear();
}

void ReleaseWindow(JNIEnv* env, Context* ctx) {
  if (ctx->window) ANativeWindow_release(ctx->window);
  if (ctx->surface) env->DeleteGlobalRef(ctx->surface);
  ctx->window = nullptr;
  ctx->surface = nullptr;
  ctx->window_width = ctx->window_height = 0;
  ctx->data_space = 0;
}

inline int AlignTo16(int v) { return (v + 15) & ~15; }

// Copia un plano a la ventana. 'step' > 1 submuestrea (croma 4:2:2/4:4:4 → 4:2:0).
void CopyPlane(const Dav1dPicture& p, const uint8_t* src, ptrdiff_t src_stride,
               uint8_t* dst, int dst_stride, int width, int height,
               int step_x, int step_y) {
  if (p.p.bpc == 8) {
    for (int y = 0; y < height; y++) {
      const uint8_t* s = src + (ptrdiff_t)y * step_y * src_stride;
      if (step_x == 1) {
        memcpy(dst, s, width);
      } else {
        for (int x = 0; x < width; x++) dst[x] = s[x * step_x];
      }
      dst += dst_stride;
    }
  } else {
    // 10 bits → 8 bits con redondeo. Bucle simple: clang lo vectoriza (NEON) a -O2.
    const int shift = p.p.bpc - 8;
    const int round = 1 << (shift - 1);
    for (int y = 0; y < height; y++) {
      const uint16_t* s = reinterpret_cast<const uint16_t*>(
          src + (ptrdiff_t)y * step_y * src_stride);
      if (step_x == 1) {
        for (int x = 0; x < width; x++) {
          const int v = (s[x] + round) >> shift;
          dst[x] = static_cast<uint8_t>(v > 255 ? 255 : v);
        }
      } else {
        for (int x = 0; x < width; x++) {
          const int v = (s[x * step_x] + round) >> shift;
          dst[x] = static_cast<uint8_t>(v > 255 ? 255 : v);
        }
      }
      dst += dst_stride;
    }
  }
}

void FillPlane(uint8_t* dst, int dst_stride, int width, int height, uint8_t value) {
  for (int y = 0; y < height; y++, dst += dst_stride) memset(dst, value, width);
}

}  // namespace

FUNC(jlong, nativeOpen, jint threads, jint maxFrameDelay) {
  Dav1dSettings s;
  dav1d_default_settings(&s);
  s.n_threads = threads;              // 0 = automático (núcleos disponibles).
  s.max_frame_delay = maxFrameDelay;  // 0 = automático.
  s.apply_grain = 1;                  // Grano sintético del encode (habitual en BD): como Kodi.
  auto* ctx = new Context();
  const int res = dav1d_open(&ctx->dav1d, &s);
  if (res < 0) {
    LOGE("dav1d_open: %d", res);
    delete ctx;
    return 0;
  }
  ctx->set_data_space = reinterpret_cast<SetDataSpaceFn>(
      dlsym(RTLD_DEFAULT, "ANativeWindow_setBuffersDataSpace"));
  LOGI("dav1d %s abierto (threads=%d)", dav1d_version(), threads);
  return reinterpret_cast<jlong>(ctx);
}

FUNC(void, nativeClose, jlong jctx) {
  auto* ctx = reinterpret_cast<Context*>(jctx);
  if (!ctx) return;
  {
    std::lock_guard<std::mutex> lock(ctx->slot_mutex);
    for (int i = 0; i < kMaxSlots; i++) {
      if (ctx->used[i]) dav1d_picture_unref(&ctx->slots[i]);
      ctx->used[i] = false;
    }
  }
  DropReady(ctx);
  dav1d_close(&ctx->dav1d);
  ReleaseWindow(env, ctx);
  delete ctx;
}

FUNC(void, nativeFlush, jlong jctx) {
  auto* ctx = reinterpret_cast<Context*>(jctx);
  dav1d_flush(ctx->dav1d);
  DropReady(ctx);
}

// Envía un paquete. Devuelve 0 o un error dav1d (<0).
FUNC(jint, nativeDecode, jlong jctx, jobject buffer, jint size, jlong timeUs) {
  auto* ctx = reinterpret_cast<Context*>(jctx);
  const auto* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
  if (!src || size <= 0) return DAV1D_ERR(EINVAL);

  Dav1dData data = {};
  uint8_t* dst = dav1d_data_create(&data, size);
  if (!dst) return DAV1D_ERR(ENOMEM);
  memcpy(dst, src, size);
  data.m.timestamp = timeUs;

  int guard = 0;
  while (data.sz > 0) {
    int res = dav1d_send_data(ctx->dav1d, &data);
    if (res == 0) continue;
    if (res == DAV1D_ERR(EAGAIN) && guard++ < 64) {
      // Cola llena: hay que sacar una imagen antes de poder meter más datos.
      // Con varios hilos, la 1ª llamada no espera; la 2ª seguida sí (espera al fotograma más antiguo).
      Dav1dPicture pic = {};
      int got = dav1d_get_picture(ctx->dav1d, &pic);
      if (got == DAV1D_ERR(EAGAIN)) got = dav1d_get_picture(ctx->dav1d, &pic);
      if (got == 0) {
        ctx->ready.push_back(pic);
      } else if (got != DAV1D_ERR(EAGAIN)) {
        dav1d_data_unref(&data);
        return got;
      }
      continue;
    }
    dav1d_data_unref(&data);
    return res;
  }
  // Recoge una imagen si ya está lista (no bloquea justo después de un send).
  Dav1dPicture pic = {};
  const int got = dav1d_get_picture(ctx->dav1d, &pic);
  if (got == 0) {
    ctx->ready.push_back(pic);
  } else if (got != DAV1D_ERR(EAGAIN)) {
    return got;
  }
  return 0;
}

// Saca la siguiente imagen lista a un slot. info = {slot, timeUs, ancho, alto}.
// Devuelve 1 si hay imagen, 0 si aún no, <0 si error.
FUNC(jint, nativeDequeue, jlong jctx, jlongArray jinfo) {
  auto* ctx = reinterpret_cast<Context*>(jctx);
  if (ctx->ready.empty()) return 0;
  Dav1dPicture pic = ctx->ready.front();
  ctx->ready.pop_front();
  if (pic.p.bpc > 10) {
    dav1d_picture_unref(&pic);
    return DAV1D_ERR(ENOTSUP);
  }
  int slot = -1;
  {
    std::lock_guard<std::mutex> lock(ctx->slot_mutex);
    for (int i = 0; i < kMaxSlots; i++) {
      if (!ctx->used[i]) {
        slot = i;
        break;
      }
    }
    if (slot < 0) {
      dav1d_picture_unref(&pic);
      return DAV1D_ERR(ENOSPC);
    }
    ctx->slots[slot] = pic;
    ctx->used[slot] = true;
  }
  jlong info[4] = {slot, pic.m.timestamp, pic.p.w, pic.p.h};
  env->SetLongArrayRegion(jinfo, 0, 4, info);
  return 1;
}

FUNC(void, nativeRelease, jlong jctx, jint slot) {
  auto* ctx = reinterpret_cast<Context*>(jctx);
  if (slot < 0 || slot >= kMaxSlots) return;
  std::lock_guard<std::mutex> lock(ctx->slot_mutex);
  if (ctx->used[slot]) {
    dav1d_picture_unref(&ctx->slots[slot]);
    ctx->used[slot] = false;
  }
}

// Pinta el slot en la Surface. 0 = ok, <0 = error.
FUNC(jint, nativeRender, jlong jctx, jint slot, jobject surface) {
  auto* ctx = reinterpret_cast<Context*>(jctx);
  if (slot < 0 || slot >= kMaxSlots) return -1;

  if (!ctx->surface || !env->IsSameObject(ctx->surface, surface)) {
    ReleaseWindow(env, ctx);
    ctx->window = ANativeWindow_fromSurface(env, surface);
    if (!ctx->window) return -2;
    ctx->surface = env->NewGlobalRef(surface);
  }

  std::lock_guard<std::mutex> lock(ctx->slot_mutex);
  if (!ctx->used[slot]) return -3;
  const Dav1dPicture& p = ctx->slots[slot];
  const int w = p.p.w;
  const int h = p.p.h;

  if (ctx->window_width != w || ctx->window_height != h) {
    if (ANativeWindow_setBuffersGeometry(ctx->window, w, h, kImageFormatYV12)) return -4;
    ctx->window_width = w;
    ctx->window_height = h;
  }
  const int32_t data_space = DataSpaceFor(p);
  if (ctx->set_data_space && data_space != ctx->data_space) {
    const int32_t r = ctx->set_data_space(ctx->window, data_space);
    LOGI("dataspace 0x%x (bpc=%d pri=%d trc=%d mtrx=%d) -> %d", data_space, p.p.bpc,
         p.seq_hdr ? p.seq_hdr->pri : -1, p.seq_hdr ? p.seq_hdr->trc : -1,
         p.seq_hdr ? p.seq_hdr->mtrx : -1, r);
    ctx->data_space = data_space;
  }

  ANativeWindow_Buffer buf;
  if (ANativeWindow_lock(ctx->window, &buf, nullptr) || !buf.bits) return -5;
  auto* bits = static_cast<uint8_t*>(buf.bits);

  // YV12: Y, luego V, luego U. Croma a la mitad, con stride alineado a 16.
  const int y_w = std::min(w, buf.width);
  const int y_h = std::min(h, buf.height);
  CopyPlane(p, static_cast<const uint8_t*>(p.data[0]), p.stride[0], bits, buf.stride,
            y_w, y_h, 1, 1);

  const int c_stride = AlignTo16(buf.stride / 2);
  const int c_h = std::min((buf.height + 1) / 2, (h + 1) / 2);
  const int c_w = std::min((buf.width + 1) / 2, (w + 1) / 2);
  uint8_t* v_dst = bits + buf.stride * buf.height;
  uint8_t* u_dst = v_dst + c_stride * ((buf.height + 1) / 2);

  if (p.p.layout == DAV1D_PIXEL_LAYOUT_I400) {
    FillPlane(v_dst, c_stride, c_w, c_h, 128);
    FillPlane(u_dst, c_stride, c_w, c_h, 128);
  } else {
    const int step_x = p.p.layout == DAV1D_PIXEL_LAYOUT_I444 ? 2 : 1;
    const int step_y = p.p.layout == DAV1D_PIXEL_LAYOUT_I420 ? 1 : 2;
    CopyPlane(p, static_cast<const uint8_t*>(p.data[2]), p.stride[1], v_dst, c_stride,
              c_w, c_h, step_x, step_y);
    CopyPlane(p, static_cast<const uint8_t*>(p.data[1]), p.stride[1], u_dst, c_stride,
              c_w, c_h, step_x, step_y);
  }

  if (ANativeWindow_unlockAndPost(ctx->window)) return -6;
  return 0;
}
