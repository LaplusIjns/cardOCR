import { ViewConfig } from '@vaadin/hilla-file-router/types.js';
import { Button, Notification, PasswordField, TextField } from '@vaadin/react-components';
import { FormEvent, useState } from 'react';
import { AuthService } from 'Frontend/generated/endpoints';
import { Link, useNavigate } from 'react-router';

export const config: ViewConfig = {
  menu: { exclude: true },
  title: '註冊',
};

export default function RegisterView() {
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const submit = async (event?: FormEvent) => {
    event?.preventDefault();

    if (!/^[A-Za-z0-9._-]{3,50}$/.test(username.trim())) {
      Notification.show('帳號需為 3–50 字元，且只能使用英文、數字、句點、底線或連字號', {
        theme: 'error',
        position: 'top-center',
      });
      return;
    }
    if (password.length < 8 || password.length > 72) {
      Notification.show('密碼長度需為 8–72 字元', { theme: 'error', position: 'top-center' });
      return;
    }
    if (password !== confirmPassword) {
      Notification.show('兩次輸入的密碼不一致', { theme: 'error', position: 'top-center' });
      return;
    }

    setSubmitting(true);
    try {
      const result = await AuthService.register({ username: username.trim(), password });
      if (!result) {
        throw new Error('伺服器未回傳註冊結果');
      }
      Notification.show(result.message ?? (result.success ? '註冊成功' : '註冊失敗'), {
        theme: result.success ? 'success' : 'error',
        position: 'top-center',
      });
      if (result.success) {
        setUsername('');
        setPassword('');
        setConfirmPassword('');
        navigate('/login', { replace: true });
      }
    } catch {
      Notification.show('註冊失敗，請稍後再試', { theme: 'error', position: 'top-center' });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="flex items-center justify-center w-full p-l box-border">
      <form className="flex flex-col gap-m w-full" style={{ maxWidth: '26rem' }} onSubmit={submit}>
        <div>
          <h2 className="m-0">建立帳號</h2>
          <p className="text-secondary">註冊後即可使用你的帳號登入服務。</p>
        </div>
        <TextField
          label="帳號"
          value={username}
          onValueChanged={(event) => setUsername(event.detail.value)}
          autocomplete="username"
          required
        />
        <PasswordField
          label="密碼"
          helperText="至少 8 個字元"
          value={password}
          onValueChanged={(event) => setPassword(event.detail.value)}
          autocomplete="new-password"
          required
        />
        <PasswordField
          label="確認密碼"
          value={confirmPassword}
          onValueChanged={(event) => setConfirmPassword(event.detail.value)}
          autocomplete="new-password"
          required
        />
        <Button theme="primary" disabled={submitting} onClick={() => void submit()}>
          {submitting ? '註冊中…' : '註冊'}
        </Button>
        <p className="text-center text-secondary">
          已經有帳號？ <Link to="/login">返回登入</Link>
        </p>
      </form>
    </main>
  );
}
