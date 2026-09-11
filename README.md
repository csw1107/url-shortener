# url-shortener

긴 URL을 짧은 코드로 바꿔주는 웹 서비스입니다.
애플리케이션 개발부터 컨테이너화 · 쿠버네티스 배포 · CI/CD · GitOps까지
배포 파이프라인 전체를 직접 구성했습니다.

## 동작

```
POST /shorten   { "url": "https://..." }  →  6자리 코드 발급
GET  /{code}                              →  원래 주소로 302 리다이렉트
```

## 아키텍처

```
개발자 git push
   │
   ├─▶ GitHub Actions ──▶ 이미지 빌드 ──▶ Docker Hub
   │                                         │
   └─▶ ArgoCD (Git 감시) ────────────────────┘
                │
                ▼
         Kubernetes (RKE2)
         ├── url-shortener  ×2  (Deployment + Service)
         └── postgres       ×1  (공용 데이터 저장소)
```

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 애플리케이션 | Java 21, Spring Boot, Spring Data JPA |
| 데이터베이스 | PostgreSQL 16 (운영) / H2 (로컬 개발) |
| 빌드 | Gradle |
| 컨테이너 | Docker (멀티스테이지 빌드) |
| 오케스트레이션 | Kubernetes (RKE2) |
| CI | GitHub Actions |
| CD | ArgoCD (GitOps) |
| 레지스트리 | Docker Hub |

## 구축 과정

커밋 이력에 단계별로 남아 있습니다.

| 단계 | 내용 |
|---|---|
| 1 | URL 단축 · 리다이렉트 기능 구현 |
| 2 | Docker 멀티스테이지 빌드, 설정 외부화 |
| 3 | 쿠버네티스 배포 (Deployment, Service, probe, 리소스 제한) |
| 4 | GitHub Actions로 이미지 빌드 · 푸시 자동화 |
| 5 | ArgoCD Application 구성 (자동 동기화) |
| 6 | PostgreSQL 연동 — Pod 간 데이터 공유 |

## 주요 설계 판단

**멀티스테이지 빌드로 이미지 크기 절감**
빌드에는 JDK와 Gradle이 필요하지만 실행에는 JRE와 jar 파일만 있으면 됩니다.
빌드 단계와 실행 단계를 분리해 최종 이미지를 **169MB**로 줄였습니다.

**설정을 코드에서 분리**
서비스 주소와 DB 접속 정보를 코드에 넣지 않고 환경변수로 주입합니다.
같은 이미지를 로컬 · 클러스터 어디서든 설정만 바꿔 실행할 수 있습니다.

**이미지 태그에 커밋 해시 사용**
`latest` 외에 `${{ github.sha }}` 태그를 함께 붙여,
지금 배포된 이미지가 어느 커밋인지 추적하고 되돌릴 수 있게 했습니다.

**비밀번호를 저장소에 두지 않음**
DB 계정 정보는 Kubernetes Secret으로 분리하고,
매니페스트에서는 `secretKeyRef`로 참조만 합니다.

**probe와 리소스 제한 명시**
readiness / liveness probe로 준비되지 않은 Pod에 트래픽이 가지 않도록 하고,
requests / limits를 지정해 자원 할당을 예측 가능하게 했습니다.

## 직접 겪은 문제와 해결

**1. Pod를 2개로 늘리자 방금 만든 코드가 404로 조회됨**

H2 인메모리 DB를 쓰고 있어 각 Pod가 자기 메모리에만 데이터를 저장하고 있었습니다.
A Pod에서 발급한 코드를 B Pod가 조회하면 없다고 응답했습니다.

→ PostgreSQL을 별도 Pod로 띄우고 두 Pod가 같은 DB를 보도록 변경했습니다.
Pod별로 port-forward를 걸어 **A에서 발급한 코드를 B에서 조회해 302를 확인**했습니다.

배운 점: 애플리케이션을 여러 개 띄우려면 상태를 애플리케이션 밖으로 빼야 합니다.

**2. CI 완료 전에 배포해 드라이버 없는 이미지가 배포됨**

`ClassNotFoundException: org.postgresql.Driver` 로 Pod가 재시작을 반복했습니다.
GitHub Actions가 새 이미지를 다 만들기 전에 `kubectl apply`를 실행해,
`latest` 태그가 아직 이전 빌드를 가리키고 있었던 것이 원인이었습니다.

→ CI 완료를 확인한 뒤 `kubectl rollout restart`로 이미지를 다시 받아 해결했습니다.

배운 점: `latest` 태그는 "언제의 latest인지"를 보장하지 않습니다.
커밋 해시 태그를 배포에 사용하면 이 문제가 구조적으로 사라집니다.

**3. 클러스터 자원 할당량 초과**

`exceeded quota: pods=20, limited: 20` 으로 Pod가 스케줄되지 않았습니다.

→ ArgoCD 구성 요소 중 이 프로젝트에 불필요한 것(dex, notifications, applicationset)을
0으로 줄여 자리를 확보했습니다.
또한 스토리지 할당량(25Gi)이 소진되어 PVC를 만들 수 없었고,
우선순위를 따져 emptyDir로 진행했습니다(아래 한계 참고).

배운 점: 자원 제약은 "안 됩니다"가 아니라 "무엇을 포기할지 정하는" 문제입니다.

## 실행 방법

### 로컬

```bash
./gradlew bootRun
```

H2 인메모리 DB로 기동합니다. 기본 포트는 `application.properties`의 `server.port`.

### Docker

```bash
docker build -t url-shortener:1.0 .
docker run -d -p 8082:8081 -e APP_BASE_URL=http://localhost:8082 url-shortener:1.0
```

### Kubernetes

```bash
kubectl create secret generic postgres-secret \
  --from-literal=POSTGRES_USER=<user> \
  --from-literal=POSTGRES_PASSWORD=<password> \
  --from-literal=POSTGRES_DB=<db>

kubectl apply -f k8s/
```

ArgoCD를 사용하는 경우:

```bash
kubectl apply -f argocd/application.yaml
```

## 한계와 개선 과제

- **DB 영속성 없음** — 클러스터 스토리지 할당량 제한으로 PVC를 확보하지 못해
  emptyDir로 구성했습니다. DB Pod 재시작 시 데이터가 소실됩니다.
  운영 환경에서는 PVC 또는 관리형 DB가 필요합니다.
- **배포 태그가 `latest`** — 커밋 해시 태그는 푸시하고 있으나 배포에는 사용하지 않고 있습니다.
  CI가 매니페스트의 이미지 태그를 갱신하도록 개선할 계획입니다.
- **모니터링 미구성** — Prometheus 메트릭 노출과 대시보드 구성이 남아 있습니다.
- **외부 노출 미구성** — 현재 `port-forward`로만 접근합니다. Ingress 구성이 필요합니다.