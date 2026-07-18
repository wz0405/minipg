# minipg frontend (Flutter)

결제 발사대 — 화면은 최소, 결제 체험과 리포트 확인에 집중한다.

## 계획 화면

| 화면 | 내용 | 백엔드 |
|---|---|---|
| 결제 체험 | WebView로 호스티드 결제 페이지(`/checkout.html`) 로드 | api :8080 |
| 거래내역 | 일자/가맹점별 거래 목록, 취소 버튼 | GET /api/report/tr |
| 정산 리포트 | 일정산 목록 + 건별 상세 + 월 마진 | GET /api/report/stmt* |
| 대사 결과 | 불일치 요약/목록 | GET /api/report/recon |
| 관리 | 시딩 실행, 배치 수동 트리거 | POST /api/admin/* |

## 스캐폴드

```bash
flutter create --org com.minipg --project-name minipg_app .
```

결제창(나이스 JS SDK)은 네이티브 위젯이 아니라 WebView(webview_flutter)로 띄운다
— 인증창 리다이렉트 체인이 웹 기준으로 설계되어 있기 때문.
