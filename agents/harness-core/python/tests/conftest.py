import sys
from pathlib import Path

# harness_core 는 아직 설치형 패키지가 아니다 (배치 방식 미결정 — README 참조).
# 테스트는 소스 위치를 직접 얹어 돈다.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
