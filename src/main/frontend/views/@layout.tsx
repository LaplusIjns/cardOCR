import { createMenuItems, useViewConfig } from '@vaadin/hilla-file-router/runtime.js';
import { effect, signal } from '@vaadin/hilla-react-signals';
import {
  AppLayout,
  DrawerToggle,
  Icon,
  SideNav,
  SideNavItem,
  Button,
  HorizontalLayout,
} from '@vaadin/react-components';
import { Suspense, useEffect, useState, type CSSProperties } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router';
import { getSession, logout, type SessionInfo } from 'Frontend/auth';

const documentTitleSignal = signal('');
const savedTheme = localStorage.getItem('darkMode');
const darkModeSignal = signal(savedTheme === 'true');
effect(() => {
  document.title = documentTitleSignal.value;
  document.documentElement.setAttribute('theme', darkModeSignal.value ? 'dark' : 'light');
  localStorage.setItem('darkMode', darkModeSignal.value ? 'true' : 'false');
});

// Publish for Vaadin to use
(globalThis as any).Vaadin.documentTitleSignal = documentTitleSignal;

export default function MainLayout() {
  const currentTitle = useViewConfig()?.title;
  const navigate = useNavigate();
  const location = useLocation();
  const [isMobile, setIsMobile] = useState(false);
  const [session, setSession] = useState<SessionInfo | null>(null);
  const publicRoute = location.pathname === '/login' || location.pathname === '/register';

  useEffect(() => {
    const query = `(max-width: 767px)`;
    const media = globalThis.matchMedia(query);
    const handleChange = () => setIsMobile(media.matches);

    setIsMobile(media.matches);
    media.addEventListener('change', handleChange);
    return () => media.removeEventListener('change', handleChange); // 清理監聽
  }, []);

  useEffect(() => {
    const savedTheme = localStorage.getItem('darkMode');
    darkModeSignal.value = savedTheme === 'true';
    if (currentTitle) {
      documentTitleSignal.value = currentTitle;
    }
  }, [currentTitle]);

  useEffect(() => {
    let active = true;
    getSession()
      .then((currentSession) => {
        if (!active) return;
        setSession(currentSession);
        if (!currentSession.authenticated && !publicRoute) {
          navigate('/login', { replace: true });
        }
      })
      .catch(() => {
        if (!active) return;
        setSession({ authenticated: false, username: null });
        if (!publicRoute) navigate('/login', { replace: true });
      });
    return () => {
      active = false;
    };
  }, [location.pathname, navigate, publicRoute]);

  const toggleDarkMode = () => {
    darkModeSignal.value = !darkModeSignal.value;
  };

  const signOut = async () => {
    await logout();
    setSession({ authenticated: false, username: null });
    navigate('/login', { replace: true });
  };

  if (publicRoute) {
    return (
      <Suspense>
        <Outlet />
      </Suspense>
    );
  }

  if (!session?.authenticated) {
    return <div className="flex items-center justify-center h-full">檢查登入狀態…</div>;
  }

  return (
    <AppLayout
      primarySection="drawer"
      style={{ '--vaadin-app-layout-touch-optimized': isMobile ? 'true' : 'false' } as CSSProperties}
    >
      {isMobile ? (
        <HorizontalLayout slot="navbar touch-optimized" className="flex-row" style={{ width: '100%' }}>
          {createMenuItems().map(({ to, title, icon }) => (
            <SideNav onNavigate={({ path }) => navigate(path!)} location={location}>
              <SideNavItem path={to} key={to}>
                {icon ? <Icon src={icon} slot="prefix"></Icon> : <></>}
                {title}
              </SideNavItem>
            </SideNav>
          ))}
          <Button theme="contrast" onClick={toggleDarkMode}>
            <Icon src={darkModeSignal.value ? 'line-awesome/svg/sun-solid.svg' : 'line-awesome/svg/moon-solid.svg'} />
          </Button>
          <Button theme="tertiary" onClick={() => void signOut()}>
            登出
          </Button>
        </HorizontalLayout>
      ) : (
        <>
          <div slot="drawer" className="flex flex-col justify-between h-full p-m">
            <header className="flex flex-col gap-m">
              <span className="font-semibold text-l">名片 OCR</span>
              <Button theme="contrast" onClick={toggleDarkMode}>
                <Icon
                  src={darkModeSignal.value ? 'line-awesome/svg/sun-solid.svg' : 'line-awesome/svg/moon-solid.svg'}
                />
                {darkModeSignal.value ? '明亮模式' : '黑暗模式'}
              </Button>
              <span className="text-secondary">{session.username}</span>
              <Button theme="tertiary" onClick={() => void signOut()}>
                登出
              </Button>
              <SideNav onNavigate={({ path }) => navigate(path!)} location={location}>
                {createMenuItems().map(({ to, title, icon }) => (
                  <SideNavItem path={to} key={to}>
                    {icon ? <Icon src={icon} slot="prefix"></Icon> : <></>}
                    {title}
                  </SideNavItem>
                ))}
              </SideNav>
            </header>
          </div>
          <DrawerToggle slot="navbar" aria-label="Menu toggle"></DrawerToggle>
          <h1 slot="navbar" className="text-l m-0">
            {documentTitleSignal}
          </h1>
        </>
      )}

      <Suspense>
        <Outlet />
      </Suspense>
    </AppLayout>
  );
}
