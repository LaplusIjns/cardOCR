import { ViewConfig } from '@vaadin/hilla-file-router/types.js';
import { Button, Notification, PasswordField, TextField } from '@vaadin/react-components';
import { FormEvent, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { login } from 'Frontend/auth';

export const config: ViewConfig = { title: '登入', menu: { exclude: true } };

export default function LoginView() {
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const submit = async (event?: FormEvent) => {
    event?.preventDefault();
    if (!username.trim() || !password) {
      Notification.show('請輸入帳號與密碼', { theme: 'error', position: 'top-center' });
      return;
    }

    setSubmitting(true);
    try {
      await login(username.trim(), password);
      navigate('/', { replace: true });
    } catch (error) {
      Notification.show(error instanceof Error ? error.message : '登入失敗', {
        theme: 'error',
        position: 'top-center',
      });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="flex items-center justify-center w-full h-full p-l box-border">
      <form className="flex flex-col gap-m w-full" style={{ maxWidth: '26rem' }} onSubmit={submit}>
        <div>
          <h2 className="m-0">登入</h2>
          <p className="text-secondary">登入後即可使用名片 OCR。</p>
        </div>
        <TextField
          label="帳號"
          value={username}
          onValueChanged={(event) => setUsername(event.detail.value)}
          autocomplete="username"
          autofocus
          required
        />
        <PasswordField
          label="密碼"
          value={password}
          onValueChanged={(event) => setPassword(event.detail.value)}
          autocomplete="current-password"
          required
        />
        <Button theme="primary" disabled={submitting} onClick={() => void submit()}>
          {submitting ? '登入中…' : '登入'}
        </Button>
        <p className="text-center text-secondary">
          還沒有帳號？ <Link to="/register">前往註冊</Link>
        </p>
      </form>
    </main>
  );
}
