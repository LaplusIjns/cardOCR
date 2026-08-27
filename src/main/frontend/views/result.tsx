import { ViewConfig } from '@vaadin/hilla-file-router/types.js';
import { Button, Dialog, FormLayout, Grid, GridColumn, Notification, TextArea, TextField } from '@vaadin/react-components';
import { memo, useCallback, useEffect, useRef, useState } from 'react';
import { ProcessService } from 'Frontend/generated/endpoints';
import BusinessCardDTO from 'Frontend/generated/com/github/laplusijns/card/BusinessCardDTO';

export const config: ViewConfig = {
  menu: { order: 1, icon: 'line-awesome/svg/address-card-solid.svg' },
  title: '名片辨識結果',
};

const ImageRenderer = memo(function ImageRenderer({ item }: Readonly<{ item: BusinessCardDTO }>) {
  return <img src={`thumbnail/${item.imageUrl}`} alt="名片" style={{ width: 80, height: 50, objectFit: 'cover', borderRadius: 4, cursor: 'pointer' }} onClick={() => (globalThis as any).setSelectedPreview?.(item.imageUrl)} />;
});

const ActionRenderer = memo(function ActionRenderer({ item, onDelete, onEdit, onRecognize }: Readonly<{
  item: BusinessCardDTO;
  onDelete: (key: string) => void;
  onEdit: (item: BusinessCardDTO) => void;
  onRecognize: (item: BusinessCardDTO) => void;
}>) {
  if (item.id == null) return null;
  const recognizing = item.status === '重新辨識中';
  return <div className="flex gap-s">
    <Button theme="primary small" onClick={() => onEdit(item)}>編輯</Button>
    <Button theme="small" disabled={recognizing} onClick={() => onRecognize(item)}>{recognizing ? '辨識中…' : '重新辨識'}</Button>
    <Button theme="error small" onClick={async () => {
    if (!confirm(`確定要刪除 ${item.name || '這張名片'} 嗎？`)) return;
    try {
      await ProcessService.deleteCard(item.id!);
      onDelete(item.key ?? '');
      Notification.show('名片已刪除', { duration: 2000, theme: 'success', position: 'top-center' });
    } catch (error) {
      console.error(error);
      Notification.show('刪除失敗', { theme: 'error', position: 'top-center' });
    }
    }}>刪除</Button>
  </div>;
});

type EditableField = 'companyName' | 'name' | 'jobTitle' | 'telephone' | 'mobilePhone' | 'fax' | 'email' | 'address' | 'notes';

const fields: ReadonlyArray<{ key: EditableField; label: string; area?: boolean }> = [
  { key: 'companyName', label: '公司名稱' },
  { key: 'name', label: '姓名' },
  { key: 'jobTitle', label: '職稱' },
  { key: 'telephone', label: '電話' },
  { key: 'mobilePhone', label: '行動電話' },
  { key: 'fax', label: '傳真' },
  { key: 'email', label: 'EMAIL' },
  { key: 'address', label: '地址' },
  { key: 'notes', label: '備註', area: true },
];

export default function ResultView() {
  const [cards, setCards] = useState<BusinessCardDTO[]>([]);
  const [selectedPreview, setSelectedPreview] = useState<string | null>(null);
  const [previewRotation, setPreviewRotation] = useState(0);
  const [editingCard, setEditingCard] = useState<BusinessCardDTO | null>(null);
  const [saving, setSaving] = useState(false);
  const subscriptionRef = useRef<any>(null);
  const subscriptionPromiseRef = useRef<Promise<string> | null>(null);
  const mountedRef = useRef(false);

  const applyCardUpdate = useCallback((update: BusinessCardDTO) => {
    setCards((previous) => previous.some((item) => item.key === update.key)
      ? previous.map((item) => item.key === update.key ? update : item)
      : [update, ...previous]);
  }, []);

  const ensureSubscription = useCallback(() => {
    if (!subscriptionPromiseRef.current) {
      subscriptionPromiseRef.current = ProcessService.jsessionId().catch((error) => {
        subscriptionPromiseRef.current = null;
        throw error;
      });
    }
    return subscriptionPromiseRef.current.then((sessionId: string) => {
      if (mountedRef.current) {
        subscriptionRef.current ??= ProcessService.cardSubscription(sessionId).onNext(applyCardUpdate);
      }
      return sessionId;
    });
  }, [applyCardUpdate]);

  useEffect(() => {
    (globalThis as any).setSelectedPreview = (imageUrl: string) => {
      setPreviewRotation(0);
      setSelectedPreview(imageUrl);
    };
    return () => {
      delete (globalThis as any).setSelectedPreview;
    };
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    ProcessService.data().then((items) => setCards((items ?? []).filter((item): item is BusinessCardDTO => item != null)));
    void ensureSubscription().catch((error) => console.error('無法訂閱名片辨識更新', error));
    return () => {
      mountedRef.current = false;
      subscriptionRef.current?.cancel();
      subscriptionRef.current = null;
    };
  }, [ensureSubscription]);

  const handleDelete = useCallback((key: string) => setCards((previous) => previous.filter((card) => card.key !== key)), []);
  const handleRecognize = useCallback(async (item: BusinessCardDTO) => {
    if (item.id == null || !confirm(`重新辨識將覆蓋 ${item.name || '這張名片'} 的目前資料，確定繼續嗎？`)) return;
    setCards((previous) => previous.map((card) => card.key === item.key ? { ...card, status: '重新辨識中' } : card));
    try {
      await ProcessService.reRecognize(item.id, await ensureSubscription());
    } catch (error) {
      console.error(error);
      setCards((previous) => previous.map((card) =>
        card.key === item.key && card.status === '重新辨識中' ? { ...card, status: item.status } : card));
      Notification.show('無法重新辨識', { theme: 'error', position: 'top-center' });
    }
  }, [ensureSubscription]);
  const actionRenderer = useCallback(({ item }: { item: BusinessCardDTO }) =>
    <ActionRenderer item={item} onDelete={handleDelete} onEdit={setEditingCard} onRecognize={handleRecognize} />,
  [handleDelete, handleRecognize]);

  const saveEdit = async () => {
    if (editingCard?.id == null) return;
    setSaving(true);
    try {
      const updated = await ProcessService.updateCard(editingCard.id, {
        companyName: editingCard.companyName ?? '', name: editingCard.name ?? '', jobTitle: editingCard.jobTitle ?? '',
        telephone: editingCard.telephone ?? '', mobilePhone: editingCard.mobilePhone ?? '', fax: editingCard.fax ?? '',
        email: editingCard.email ?? '', address: editingCard.address ?? '', notes: editingCard.notes ?? '',
      });
      if (updated) setCards((previous) => previous.map((card) => card.key === updated.key ? updated : card));
      setEditingCard(null);
      Notification.show('名片資料已儲存', { duration: 2000, theme: 'success', position: 'top-center' });
    } catch (error) {
      console.error(error);
      Notification.show('儲存失敗', { theme: 'error', position: 'top-center' });
    } finally {
      setSaving(false);
    }
  };

  return <div className="flex flex-col h-full box-border w-full p-m">
    <h2 className="mb-m">名片辨識結果</h2>
    <Grid items={cards} className="w-full" theme="row-stripes wrap-cell-content compact">
      <GridColumn header="操作" renderer={actionRenderer} autoWidth flexGrow={0} />
      <GridColumn header="圖片" renderer={ImageRenderer} autoWidth flexGrow={0} />
      <GridColumn header="公司名稱" path="companyName" autoWidth />
      <GridColumn header="姓名" path="name" autoWidth />
      <GridColumn header="職稱" path="jobTitle" autoWidth />
      <GridColumn header="電話" path="telephone" autoWidth />
      <GridColumn header="行動電話" path="mobilePhone" autoWidth />
      <GridColumn header="傳真" path="fax" autoWidth />
      <GridColumn header="EMAIL" path="email" autoWidth />
      <GridColumn header="地址" path="address" autoWidth />
      <GridColumn header="備註" path="notes" autoWidth />
      <GridColumn header="狀態" path="status" autoWidth />
    </Grid>
    {editingCard && <Dialog headerTitle="編輯名片資料" opened onOpenedChanged={(event: any) => {
      if (!event.detail.value && !saving) setEditingCard(null);
    }} footerRenderer={() => <div className="flex gap-s justify-end">
      <Button disabled={saving} onClick={() => setEditingCard(null)}>取消</Button>
      <Button theme="primary" disabled={saving} onClick={saveEdit}>{saving ? '儲存中…' : '儲存'}</Button>
    </div>}>
      <FormLayout responsiveSteps={[{ minWidth: '0', columns: 1 }, { minWidth: '500px', columns: 2 }]}>
        {fields.map((field) => field.area
          ? <TextArea key={field.key} label={field.label} value={editingCard[field.key] ?? ''}
              style={{ gridColumn: '1 / -1' }} onValueChanged={(event) => setEditingCard({ ...editingCard, [field.key]: event.detail.value })} />
          : <TextField key={field.key} label={field.label} value={editingCard[field.key] ?? ''}
              onValueChanged={(event) => setEditingCard({ ...editingCard, [field.key]: event.detail.value })} />)}
      </FormLayout>
    </Dialog>}
    {selectedPreview && <Dialog headerTitle="名片圖片" opened onOpenedChanged={(event: any) => {
      if (!event.detail.value) {
        setSelectedPreview(null);
        setPreviewRotation(0);
      }
    }}>
      <div className="flex flex-col gap-m items-center">
        <div className="flex gap-s items-center justify-center" role="group" aria-label="旋轉名片圖片">
          <Button onClick={() => setPreviewRotation((angle) => angle - 90)} aria-label="向左旋轉 90 度">↶ 向左轉</Button>
          <Button onClick={() => setPreviewRotation(0)} disabled={previewRotation === 0}>重設</Button>
          <Button onClick={() => setPreviewRotation((angle) => angle + 90)} aria-label="向右旋轉 90 度">向右轉 ↷</Button>
          <span aria-live="polite">{((previewRotation % 360) + 360) % 360}°</span>
        </div>
        <div style={{ width: 'min(80vw, 900px)', height: '65vh', overflow: 'auto', display: 'grid', placeItems: 'center', padding: 16, boxSizing: 'border-box' }}>
          <img src={`blob/${selectedPreview}`} alt="名片原圖" style={{ maxWidth: '100%', maxHeight: '100%', borderRadius: 8, transform: `rotate(${previewRotation}deg)`, transition: 'transform 180ms ease' }} />
        </div>
      </div>
    </Dialog>}
  </div>;
}
