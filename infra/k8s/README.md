# Kubernetes 매니페스트 (GCP 전환 준비)

> **현재 상태**: 골격만. 실제 GCP 배포는 Oracle 운영 안정화 후 진행.

## 구조

```
infra/k8s/
  base/                    플랫폼 무관 기본 매니페스트
    namespace.yaml
    configmap.yaml         비밀 아닌 환경변수
    secret.sample.yaml     ★ 실제 secret 은 절대 커밋 금지
    deployments/           앱 서비스 Deployment (서비스 1개당 1파일)
    services/              ClusterIP / LoadBalancer Service
    kustomization.yaml
  overlays/
    gcp/                   GCP 전용 overlay
      kustomization.yaml
      ingress.yaml         GKE Ingress (외부 노출)
      patches/             GCP 환경 패치 (Cloud SQL proxy 등)
```

## 같은 GHCR 이미지를 재사용

`infra/docker/docker-compose.prod.yml` 에서 Oracle 에 띄우던 동일 이미지를 GKE 에서도
그대로 사용한다.

```yaml
image: ghcr.io/<owner>/loan-service:latest
```

CI/CD 워크플로우 (`reusable-docker-publish.yml`) 가 Oracle/GCP 공통으로 GHCR push.

## Oracle → GCP 전환 시 체크리스트

| 항목 | Oracle (현재) | GCP (전환 후) |
|------|--------------|--------------|
| 컨테이너 오케스트레이션 | docker compose | GKE Standard / Autopilot |
| PostgreSQL ×6 | 컨테이너 | **Cloud SQL** (관리형) |
| Redis | 컨테이너 | **Memorystore** |
| Kafka | 컨테이너 | **Pub/Sub** 또는 Confluent Cloud |
| 이미지 레지스트리 | GHCR | GHCR 그대로 OR **Artifact Registry** |
| 모니터링 | Prometheus + Grafana | **Cloud Monitoring** + Grafana |
| 외부 진입 | api-gateway 8080 | **GKE Ingress** + Cloud Load Balancer |
| 시크릿 | `.env.prod` 파일 | **Secret Manager** + CSI driver |

## 골격 사용법

```bash
# 1. 네임스페이스 + 공통 리소스
kubectl apply -k infra/k8s/base

# 2. GCP 환경 적용
kubectl apply -k infra/k8s/overlays/gcp
```

## 새 서비스 추가 패턴

`base/deployments/customer-service.yaml` 를 복사:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: <service-name>
  namespace: internet-banking
spec:
  replicas: 1
  selector:
    matchLabels:
      app: <service-name>
  template:
    metadata:
      labels:
        app: <service-name>
    spec:
      containers:
        - name: app
          image: ghcr.io/OWNER/<service-name>:latest
          ports:
            - containerPort: <port>
          envFrom:
            - configMapRef: { name: app-config }
            - secretRef: { name: app-secrets }
          resources:
            requests: { cpu: 100m, memory: 384Mi }
            limits:   { cpu: 500m, memory: 768Mi }
```

그 다음 `base/services/<service-name>.yaml` 도 같은 패턴으로 추가하고
`base/kustomization.yaml` 의 `resources:` 목록에 등록.
