import ReactDOM from 'react-dom/client';
import 'katex/dist/katex.min.css';
import '@xterm/xterm/css/xterm.css';
import './styles.css';
import App from './App';

// 尽可能早地告诉后端「渲染层已经跑起来了」。
// 开机自启时若卡在启动画面，这条日志的有无可以直接区分是 WebView 没加载，还是初始化被阻塞。
const binding = window.go?.main?.App?.Call ?? window.go?.app?.App?.Call;
binding?.('app.frontendMounted', '[]').catch(() => undefined);

ReactDOM.createRoot(document.getElementById('root')!).render(<App />);
