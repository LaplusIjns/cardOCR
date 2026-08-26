import { ViewConfig } from '@vaadin/hilla-file-router/types.js';
import { Button, Icon } from '@vaadin/react-components';
import { useEffect, useState } from 'react';
import { ProcessService } from 'Frontend/generated/endpoints';

export const config: ViewConfig = {
  menu: { order: 2, icon: 'line-awesome/svg/file-export-solid.svg' },
  title: '匯出資料',
};

const exportUrl = (format: 'archive' | 'excel') => new URL(`api/exports/${format}`, document.baseURI).toString();

export default function ExportView() {
  const [cardCount, setCardCount] = useState<number | null>(null);

  useEffect(() => {
    ProcessService.data()
      .then((cards) => setCardCount(cards?.length ?? 0))
      .catch(() => setCardCount(null));
  }, []);

  return (
    <div className="flex flex-col box-border w-full p-l gap-l" style={{ maxWidth: 900 }}>
      <div>
        <h2 className="mb-s">匯出名片資料</h2>
        <p className="text-secondary m-0">
          {cardCount == null ? '準備匯出資料中…' : `目前共有 ${cardCount} 筆名片資料可供匯出。`}
        </p>
      </div>

      <section className="border rounded-l p-l flex flex-col gap-m">
        <div className="flex items-center gap-m">
          <Icon src="line-awesome/svg/file-archive-solid.svg" style={{ fontSize: '2.5rem' }} />
          <div>
            <h3 className="m-0">完整備份</h3>
            <p className="text-secondary mb-0">下載 ZIP 壓縮包，內含 cards.json 與 images 資料夾中的所有原始圖片。</p>
          </div>
        </div>
		<Button
		  theme="primary"
		  disabled={cardCount === 0}
		  onClick={() =>
		    globalThis.open(
		      exportUrl('archive'),
		      '_blank',
		      'noopener,noreferrer',
		    )
		  }
		>
		  <Icon slot="prefix" src="line-awesome/svg/download-solid.svg" />
		  下載 ZIP
		</Button>
      </section>

      <section className="border rounded-l p-l flex flex-col gap-m">
        <div className="flex items-center gap-m">
          <Icon src="line-awesome/svg/file-excel-solid.svg" style={{ fontSize: '2.5rem' }} />
          <div>
            <h3 className="m-0">Excel 資料表</h3>
            <p className="text-secondary mb-0">下載 XLSX 格式的名片欄位資料，不包含圖片。</p>
          </div>
        </div>
		<Button
		  theme="primary success"
		  disabled={cardCount === 0}
		  onClick={() =>
		    globalThis.open(
		      exportUrl('excel'),
		      '_blank',
		      'noopener,noreferrer',
		    )
		  }
		>
		  <Icon slot="prefix" src="line-awesome/svg/download-solid.svg" />
		  下載 Excel
		</Button>
      </section>
    </div>
  );
}
