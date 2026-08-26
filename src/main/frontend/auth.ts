export type SessionInfo = {
  authenticated: boolean;
  username: string | null;
};

const authUrl = (path: string) => new URL(`api/auth/${path}`, document.baseURI).toString();

export async function getSession(): Promise<SessionInfo> {
  const response = await fetch(authUrl('session'), { credentials: 'same-origin' });
  if (!response.ok) throw new Error('無法取得登入狀態');
  return response.json();
}

export async function login(username: string, password: string): Promise<SessionInfo> {
  const response = await fetch(authUrl('login'), {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (response.status === 401) throw new Error('帳號或密碼錯誤');
  if (!response.ok) throw new Error('登入失敗，請稍後再試');
  return response.json();
}

export async function logout(): Promise<void> {
  const response = await fetch(authUrl('logout'), { method: 'POST', credentials: 'same-origin' });
  if (!response.ok) throw new Error('登出失敗');
}
