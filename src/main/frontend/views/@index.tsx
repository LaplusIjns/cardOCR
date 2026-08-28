import { ViewConfig } from '@vaadin/hilla-file-router/types.js';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Dialog, Notification } from '@vaadin/react-components';
import { ProcessService } from 'Frontend/generated/endpoints';
import { useBlocker, BlockerFunction } from 'react-router';

const MAX_UPLOAD_FILES = 20;
const MAX_FILE_SIZE = 10 * 1024 * 1024;
const SUPPORTED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif', 'application/pdf']);

type UploadPreviewStatus = 'uploading' | 'submitted' | 'failed';

type UploadPreview = {
  id: string;
  name: string;
  url: string;
  mimeType: string;
  status: UploadPreviewStatus;
};

export const config: ViewConfig = {
  menu: { order: 0, icon: 'line-awesome/svg/camera-solid.svg' },
  title: '拍照與上傳',
};

// 封裝導航前阻止 hook
export function useBeforeNavigate(shouldBlock: () => boolean, onBeforeNavigate: () => Promise<void>) {
  const handlingRef = useRef(false);
  const blockFn: BlockerFunction = useCallback(
    ({ currentLocation, nextLocation }) =>
      shouldBlock() && currentLocation.pathname !== nextLocation.pathname,
    [shouldBlock],
  );

  const blocker = useBlocker(blockFn);

  useEffect(() => {
    if (blocker.state !== 'blocked' || handlingRef.current) return;

    handlingRef.current = true;
    void onBeforeNavigate()
      .then(() => blocker.proceed())
      .catch(() => blocker.reset())
      .finally(() => {
        handlingRef.current = false;
      });
  }, [blocker, onBeforeNavigate]);
}

export default function CameraView() {
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const jsessionidRef = useRef<string | null>(null);

  const [flash, setFlash] = useState(false);
  const flashTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [previewQueue, setPreviewQueue] = useState<PhotoTask[]>([]);
  const [uploadPreviews, setUploadPreviews] = useState<UploadPreview[]>([]);
  const [selectedUploadPreviewId, setSelectedUploadPreviewId] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const uploadPreviewUrlsRef = useRef(new Set<string>());

  type PhotoTask = {
    id: string;
    image: string;
  };

  // 上傳隊列及處理狀態
  const queueRef = useRef<PhotoTask[]>([]);
  const previewQueueRef = useRef<PhotoTask[]>([]);
  const processingPromiseRef = useRef<Promise<void> | null>(null);
  const previewTimeoutsRef = useRef(new Map<string, ReturnType<typeof setTimeout>>());

  useEffect(() => {
    ProcessService.jsessionId().then((jsessionid: string) => {
      jsessionidRef.current = jsessionid;
    });
  }, []);

  useEffect(() => {
    const previewUrls = uploadPreviewUrlsRef.current;
    return () => {
      previewUrls.forEach((url) => URL.revokeObjectURL(url));
      previewUrls.clear();
    };
  }, []);

  const sessionId = useCallback(async () => {
    if (jsessionidRef.current) return jsessionidRef.current;
    const id = await ProcessService.jsessionId();
    jsessionidRef.current = id;
    return id;
  }, []);

  const readImage = (file: File) =>
    new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => (typeof reader.result === 'string' ? resolve(reader.result) : reject(new Error('讀取圖片失敗')));
      reader.onerror = () => reject(reader.error ?? new Error('讀取圖片失敗'));
      reader.readAsDataURL(file);
    });

  const uploadImages = async (files: FileList | null) => {
    if (!files?.length) return;
    const selected = Array.from(files);
    if (fileInputRef.current) fileInputRef.current.value = '';
    if (selected.length > MAX_UPLOAD_FILES) {
      Notification.show(`一次最多上傳 ${MAX_UPLOAD_FILES} 張圖片`, {
        theme: 'error',
        position: 'top-center',
      });
      return;
    }
    const unsupported = selected.find((file) => !SUPPORTED_IMAGE_TYPES.has(file.type));
    if (unsupported) {
      Notification.show(`${unsupported.name} 不是支援的圖片格式`, { theme: 'error', position: 'top-center' });
      return;
    }
    const oversized = selected.find((file) => file.size > MAX_FILE_SIZE);
    if (oversized) {
      Notification.show(`${oversized.name} 超過 10 MB`, { theme: 'error', position: 'top-center' });
      return;
    }

    // Object URL 不需等待檔案轉成 Base64，使用者選取後即可先看到本地預覽。
    const newPreviews = selected.map<UploadPreview>((file) => {
      const url = URL.createObjectURL(file);
      uploadPreviewUrlsRef.current.add(url);
      return {
        id: crypto.randomUUID(),
        name: file.name,
        url,
        mimeType: file.type,
        status: 'uploading',
      };
    });
    const newPreviewIds = new Set(newPreviews.map((preview) => preview.id));
    setUploadPreviews((current) => [...newPreviews, ...current]);

    setUploading(true);
    try {
      const images = await Promise.all(selected.map(readImage));
      const accepted = await ProcessService.processImages(images, await sessionId());
      setUploadPreviews((current) => current.map((preview) =>
        newPreviewIds.has(preview.id) ? { ...preview, status: 'submitted' } : preview));
      Notification.show(`已上傳 ${accepted} 張圖片，正在進行 OCR`, {
        duration: 3000,
        theme: 'success',
        position: 'top-center',
      });
    } catch (error) {
      console.error(error);
      setUploadPreviews((current) => current.map((preview) =>
        newPreviewIds.has(preview.id) ? { ...preview, status: 'failed' } : preview));
      Notification.show('圖片上傳失敗，請稍後再試', { theme: 'error', position: 'top-center' });
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const closeUploadPreview = (preview: UploadPreview) => {
    if (uploadPreviewUrlsRef.current.delete(preview.url)) URL.revokeObjectURL(preview.url);
    setUploadPreviews((current) => current.filter((item) => item.id !== preview.id));
    setSelectedUploadPreviewId((current) => current === preview.id ? null : current);
  };

  const selectedUploadPreview = uploadPreviews.find((preview) => preview.id === selectedUploadPreviewId) ?? null;

  const uploadPreviewStatus = (status: UploadPreviewStatus) => {
    if (status === 'uploading') return '上傳中…';
    if (status === 'failed') return '上傳失敗';
    return '已送出辨識';
  };

  const retainCapturedPreview = useCallback((task: PhotoTask) => {
    setUploadPreviews((current) => current.some((preview) => preview.id === task.id)
      ? current
      : [{
          id: task.id,
          name: '拍攝的名片',
          url: task.image,
          mimeType: 'image/png',
          status: 'uploading',
        }, ...current]);
  }, []);

  const replacePreviewQueue = useCallback((tasks: PhotoTask[]) => {
    previewQueueRef.current = tasks;
    setPreviewQueue(tasks);
  }, []);

  // 逐張上傳，並保留同一個 Promise 供切頁流程等待，避免重複送出同一張照片。
  const processNext = useCallback((): Promise<void> => {
    if (processingPromiseRef.current) return processingPromiseRef.current;

    const processQueue = async () => {
      while (queueRef.current.length > 0) {
        const next = queueRef.current[0];

        Notification.show('圖片已進入處理流程', {
          duration: 2000,
          theme: 'success',
          position: 'top-center',
        });

        try {
          await ProcessService.process(next.image, await sessionId());
          queueRef.current.shift();
          setUploadPreviews((current) => current.map((preview) =>
            preview.id === next.id ? { ...preview, status: 'submitted' } : preview));
        } catch (error) {
          console.error(error);
          setUploadPreviews((current) => current.map((preview) =>
            preview.id === next.id ? { ...preview, status: 'failed' } : preview));
          Notification.show('圖片處理失敗，請稍後再試', {
            theme: 'error',
            position: 'top-center',
          });
          throw error;
        }
      }
    };

    const processingPromise = processQueue().finally(() => {
      if (processingPromiseRef.current === processingPromise) {
        processingPromiseRef.current = null;
      }
    });
    processingPromiseRef.current = processingPromise;
    return processingPromise;
  }, [sessionId]);

  const queueForProcessing = useCallback(
    (task: PhotoTask) => {
      retainCapturedPreview(task);
      if (!queueRef.current.some((queuedTask) => queuedTask.id === task.id)) {
        queueRef.current.push(task);
      }
      void processNext().catch(() => undefined);
    },
    [processNext, retainCapturedPreview],
  );

  const hasPendingPhotos = useCallback(
    () => previewQueueRef.current.length > 0 || queueRef.current.length > 0,
    [],
  );

  // 預覽中的第一張照片也必須在切頁前送出；上傳失敗時留在原頁供使用者重試。
  const uploadBeforeNavigate = useCallback(async () => {
    Notification.show('正在上傳...', {
      duration: 5000,
      position: 'top-center',
      theme: 'warning',
    });

    previewTimeoutsRef.current.forEach((timeout) => clearTimeout(timeout));
    previewTimeoutsRef.current.clear();

    const previewTasks = previewQueueRef.current;
    replacePreviewQueue([]);
    previewTasks.forEach((task) => {
      retainCapturedPreview(task);
      if (!queueRef.current.some((queuedTask) => queuedTask.id === task.id)) {
        queueRef.current.push(task);
      }
    });

    await processNext();
  }, [processNext, replacePreviewQueue, retainCapturedPreview]);

  // 阻止離開頁面時隊列還在上傳
  useBeforeNavigate(hasPendingPhotos, uploadBeforeNavigate);

  // 瀏覽器關閉或重新整理時無法可靠地完成非同步上傳，因此提醒使用者不要直接離開。
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (hasPendingPhotos()) {
        e.preventDefault();
        e.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [hasPendingPhotos]);

  // 開啟攝像頭
  useEffect(() => {
    const startCamera = async () => {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: { ideal: 'environment' } },
        });
        if (videoRef.current) videoRef.current.srcObject = stream;
      } catch (err) {
        console.error('無法開啟攝像頭', err);
        Notification.show('無法開啟攝像頭', {
          duration: 5000,
          position: 'top-center',
          theme: 'error',
        });
      }
    };

    startCamera();

    return () => {
      previewTimeoutsRef.current.forEach((timeout) => clearTimeout(timeout));
      previewTimeoutsRef.current.clear();
      if (flashTimeoutRef.current) clearTimeout(flashTimeoutRef.current);
      if (videoRef.current?.srcObject) {
        const tracks = (videoRef.current.srcObject as MediaStream).getTracks();
        tracks.forEach((track) => track.stop());
      }
    };
  }, []);

  // 拍照
  const takePhoto = () => {
    const video = videoRef.current;
    const canvas = canvasRef.current;

    if (!video || !canvas) return;
    if (!video.videoWidth || !video.videoHeight) return;

    // 瀏覽器目前實際顯示的 video 尺寸
    const rect = video.getBoundingClientRect();

    const displayWidth = rect.width;
    const displayHeight = rect.height;

    // 攝影機原始尺寸
    const videoWidth = video.videoWidth;
    const videoHeight = video.videoHeight;

    const videoRatio = videoWidth / videoHeight;
    const displayRatio = displayWidth / displayHeight;

    let sourceX = 0;
    let sourceY = 0;
    let sourceWidth = videoWidth;
    let sourceHeight = videoHeight;

    /*
     * 對應 object-fit: cover
     *
     * 原始影片比較寬：
     * 左右會被裁掉
     *
     * 原始影片比較高：
     * 上下會被裁掉
     */
    if (videoRatio > displayRatio) {
      // 裁左右
      sourceHeight = videoHeight;
      sourceWidth = videoHeight * displayRatio;

      sourceX = (videoWidth - sourceWidth) / 2;
      sourceY = 0;
    } else {
      // 裁上下
      sourceWidth = videoWidth;
      sourceHeight = videoWidth / displayRatio;

      sourceX = 0;
      sourceY = (videoHeight - sourceHeight) / 2;
    }

    // 輸出圖片比例跟瀏覽器看到的一樣
    canvas.width = Math.round(displayWidth);
    canvas.height = Math.round(displayHeight);

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    ctx.drawImage(
      video,

      // 從原始 video 哪裡開始擷取
      sourceX,
      sourceY,
      sourceWidth,
      sourceHeight,

      // 畫到 canvas
      0,
      0,
      canvas.width,
      canvas.height,
    );

    const imageData = canvas.toDataURL('image/png');

    const task: PhotoTask = {
      id: crypto.randomUUID(),
      image: imageData,
    };

    // 閃光
    setFlash(true);

    if (flashTimeoutRef.current) {
      clearTimeout(flashTimeoutRef.current);
    }

    flashTimeoutRef.current = globalThis.setTimeout(() => {
      setFlash(false);
      flashTimeoutRef.current = null;
    }, 100);

    const previousPreview = previewQueueRef.current;

    replacePreviewQueue([task]);

    previousPreview.forEach(queueForProcessing);

    // 5 秒後自動加入處理隊列
    const previewTimeout = globalThis.setTimeout(() => {
      previewTimeoutsRef.current.delete(task.id);
      if (!previewQueueRef.current.some((previewTask) => previewTask.id === task.id)) return;

      replacePreviewQueue(previewQueueRef.current.filter((previewTask) => previewTask.id !== task.id));
      queueForProcessing(task);
    }, 5000);
    previewTimeoutsRef.current.set(task.id, previewTimeout);
  };

  const deletePreview = () => {
    const task = previewQueueRef.current.at(-1);
    if (!task) return;

    const previewTimeout = previewTimeoutsRef.current.get(task.id);
    if (previewTimeout) clearTimeout(previewTimeout);
    previewTimeoutsRef.current.delete(task.id);
    replacePreviewQueue(previewQueueRef.current.filter((previewTask) => previewTask.id !== task.id));
  };

  return (
    <div className="flex flex-col h-full items-center p-l text-center box-border">
      <div style={{ position: 'relative', height: '90vh', width: '100%' }}>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/jpeg,image/png,image/webp,image/gif,application/pdf"
          multiple
          hidden
          onChange={(event) => void uploadImages(event.currentTarget.files)}
        />
        <video
          ref={videoRef}
          autoPlay
          playsInline
          style={{ height: '90vh', width: '100%', objectFit: 'cover', display: 'block' }}
        />

        {flash && (
          <div
            style={{
              position: 'absolute',
              inset: 0,
              backgroundColor: 'white',
              opacity: 0.6,
              pointerEvents: 'none',
              zIndex: 10,
            }}
          />
        )}

        <Button
          onClick={takePhoto}
          className="text-center bg-contrast-40 border border-error text-error absolute font-bold text-3xl shadow-xs rounded-l p-l"
          style={{ bottom: '3%', left: '50%', transform: 'translateX(-50%)' }}>
          <span className="text-primary-contrast">●</span> 拍照
        </Button>
        <Button
          theme="primary"
          disabled={uploading}
          onClick={() => fileInputRef.current?.click()}
          className="absolute font-bold text-xl shadow-xs rounded-l p-m"
          style={{ top: '3%', right: '3%' }}>
          {uploading ? '上傳中…' : '上傳圖片或 PDF'}
        </Button>

        {uploadPreviews.length > 0 && (
          <section
            aria-label="上傳圖片預覽"
            style={{
              position: 'absolute',
              zIndex: 20,
              top: 'calc(3% + 64px)',
              left: '3%',
              right: '3%',
              padding: 12,
              borderRadius: 12,
              background: 'color-mix(in srgb, var(--lumo-base-color) 92%, transparent)',
              boxShadow: 'var(--lumo-box-shadow-m)',
              textAlign: 'left',
            }}>
            <div className="flex flex-wrap gap-s items-center justify-between mb-s">
              <strong>上傳預覽</strong>
              <span className="text-secondary text-s">辨識會在背景進行，圖片仍可隨時開啟查看</span>
            </div>
            <div className="flex gap-s" style={{ overflowX: 'auto', paddingBottom: 4 }}>
              {uploadPreviews.map((preview) => (
                <article key={preview.id} style={{ flex: '0 0 132px', minWidth: 0 }}>
                  <button
                    type="button"
                    aria-label={`預覽 ${preview.name}`}
                    onClick={() => setSelectedUploadPreviewId(preview.id)}
                    style={{
                      display: 'grid',
                      width: 132,
                      height: 84,
                      padding: 0,
                      overflow: 'hidden',
                      placeItems: 'center',
                      border: '1px solid var(--lumo-contrast-20pct)',
                      borderRadius: 8,
                      background: 'var(--lumo-contrast-5pct)',
                      cursor: 'pointer',
                    }}>
                    {preview.mimeType.startsWith('image/')
                      ? <img src={preview.url} alt="" style={{ width: '100%', height: '100%', objectFit: 'contain' }} />
                      : <span className="font-bold text-xl">PDF</span>}
                  </button>
                  <div className="flex gap-xs items-center mt-xs">
                    <span
                      title={preview.name}
                      style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {preview.name}
                    </span>
                    <button
                      type="button"
                      aria-label={`關閉 ${preview.name} 預覽`}
                      title="關閉預覽（不影響背景辨識）"
                      onClick={() => closeUploadPreview(preview)}
                      style={{ border: 0, background: 'transparent', cursor: 'pointer', fontSize: 18 }}>
                      ×
                    </button>
                  </div>
                  <small
                    className={preview.status === 'failed' ? 'text-error' : 'text-secondary'}
                    aria-live="polite">
                    {uploadPreviewStatus(preview.status)}
                  </small>
                </article>
              ))}
            </div>
          </section>
        )}
      </div>

      <canvas ref={canvasRef} style={{ display: 'none' }} />

      {previewQueue.length > 0 && (
        <Notification
          opened={previewQueue.length > 0} // <- 當 queue 為空時自動關閉
          position="top-end"
          theme="contrast no-close-button"
          duration={0}>
          <div className="flex flex-col">
            <img src={previewQueue[previewQueue.length - 1].image} style={{ width: '20vw' }} />
            <Button
              className="border border-error bg-error-50 font-bold text-2xl"
              onClick={deletePreview}>
              刪除
            </Button>
          </div>
        </Notification>
      )}

      {selectedUploadPreview && (
        <Dialog
          headerTitle={selectedUploadPreview.name}
          opened
          onOpenedChanged={(event) => {
            if (!event.detail.value) setSelectedUploadPreviewId(null);
          }}>
          <div className="flex flex-col gap-s items-center">
            <span className={selectedUploadPreview.status === 'failed' ? 'text-error' : 'text-secondary'}>
              {uploadPreviewStatus(selectedUploadPreview.status)}；關閉預覽不會中斷背景辨識
            </span>
            {selectedUploadPreview.mimeType.startsWith('image/') ? (
              <div style={{ width: 'min(80vw, 900px)', height: '65vh', display: 'grid', placeItems: 'center' }}>
                <img
                  src={selectedUploadPreview.url}
                  alt={selectedUploadPreview.name}
                  style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain', borderRadius: 8 }}
                />
              </div>
            ) : (
              <object
                data={selectedUploadPreview.url}
                type={selectedUploadPreview.mimeType}
                aria-label={`${selectedUploadPreview.name} PDF 預覽`}
                style={{ width: 'min(80vw, 900px)', height: '65vh' }}>
                <a href={selectedUploadPreview.url} target="_blank" rel="noreferrer">開啟 PDF 預覽</a>
              </object>
            )}
          </div>
        </Dialog>
      )}
    </div>
  );
}
