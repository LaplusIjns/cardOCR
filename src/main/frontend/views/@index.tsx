import { ViewConfig } from '@vaadin/hilla-file-router/types.js';
import { useEffect, useRef, useState, useCallback } from 'react';
import { Notification, Button } from '@vaadin/react-components';
import { ProcessService } from 'Frontend/generated/endpoints';
import { useBlocker, BlockerFunction } from 'react-router';

export const config: ViewConfig = {
  menu: { order: 0, icon: 'line-awesome/svg/camera-solid.svg' },
  title: '相機',
};

// 封裝導航前阻止 hook
export function useBeforeNavigate(when: boolean, onBeforeNavigate: () => void) {
  const blockFn: BlockerFunction = ({ currentLocation, nextLocation }) =>
    when && currentLocation.pathname !== nextLocation.pathname;

  const blocker = useBlocker(blockFn);

  useEffect(() => {
    if (blocker.state === 'blocked') {
      onBeforeNavigate();
      blocker.proceed();
    }
  }, [blocker, onBeforeNavigate]);
}

export default function CameraView() {
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const jsessionidRef = useRef<any>(null);

  const [flash, setFlash] = useState(false);
  const flashTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [previewQueue, setPreviewQueue] = useState<PhotoTask[]>([]);

  type PhotoTask = {
    id: string;
    image: string;
  };

  // 上傳隊列及處理狀態
  const queueRef = useRef<PhotoTask[]>([]);
  const processingRef = useRef(false);

  useEffect(() => {
    ProcessService.jsessionId().then((jsessionid: string) => {
      jsessionidRef.current = jsessionid;
    });
  }, []);

  // 處理下一張圖片
  const processNext = useCallback(async () => {
    if (processingRef.current) return;
    const next = queueRef.current[0];
    if (!next) return;

    processingRef.current = true;

    Notification.show('圖片已進入處理流程', {
      duration: 2000,
      theme: 'success',
      position: 'top-center',
    });

    const start = Date.now();

    try {
      await ProcessService.process(next.image, jsessionidRef.current);
    } catch (e) {
      console.error(e);
      Notification.show('圖片處理失敗', {
        theme: 'error',
        position: 'top-center',
      });
    } finally {
      const elapsed = Date.now() - start;
      const MIN_PREVIEW_TIME = 100;
      const remaining = Math.max(0, MIN_PREVIEW_TIME - elapsed);

      setTimeout(() => {
        processingRef.current = false;
        queueRef.current.shift();
        if (queueRef.current.length > 0) {
          queueMicrotask(processNext);
        }
      }, remaining);
    }
  }, []);

  // 阻止離開頁面時隊列還在上傳
  useBeforeNavigate(queueRef.current.length > 0, async () => {
    Notification.show('正在上傳...', {
      duration: 5000,
      position: 'top-center',
      theme: 'warning',
    });

    const uploads = queueRef.current.map((t) =>
      ProcessService.process(t.image, jsessionidRef.current).catch((err) => console.error('上傳失敗', err)),
    );

    await Promise.all(uploads);
    queueRef.current = [];
  });

  // 離開頁面時自動上傳
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (queueRef.current.length > 0) {
        e.preventDefault();
        queueRef.current.forEach((t) => navigator.sendBeacon('/process', JSON.stringify(t)));
      }
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, []);

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

    const preDate = previewQueue;

    setPreviewQueue([task]);

    preDate.forEach((oldTask) => {
      queueRef.current.push(oldTask);
    });

    processNext();

    // 5 秒後自動加入處理隊列
    globalThis.setTimeout(() => {
      setPreviewQueue((prev) => {
        const exists = prev.find((t) => t.id === task.id);

        if (!exists) return prev;

        queueRef.current.push(task);
        processNext();

        return [];
      });
    }, 5000);
  };

  return (
    <div className="flex flex-col h-full items-center p-l text-center box-border">
      <div style={{ position: 'relative', height: '90vh', width: '100%' }}>
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
              onClick={() =>
                setPreviewQueue((prev) => {
                  const newQueue = prev.slice(0, prev.length - 1); // 刪掉最後一筆
                  return newQueue;
                })
              }>
              刪除
            </Button>
          </div>
        </Notification>
      )}
    </div>
  );
}
