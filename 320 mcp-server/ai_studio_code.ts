// 1. cors 패키지 설치 필요: npm install cors
import cors from 'cors';
import express from 'express';

const app = express();

// 2. CORS 설정 (모든 요청 허용 또는 특정 도메인 허용)
app.use(cors({
  origin: '*', // 보안상 특정 도메인만 허용하려면 'https://ais-dev-...' 주소를 넣으세요
  methods: ['GET', 'POST', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'X-MCP-Token', 'ngrok-skip-browser-warning']
}));

app.use(express.json());

// ... 나머지 MCP 서버 로직