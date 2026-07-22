"""지출 패턴 경고 에이전트 (Agent 4).

최근 3개월 출금 거래를 카테고리별로 분류하고,
이번 달 지출이 직전 2개월 평균 대비 크게 증가한 항목을 감지해
'이번 달 식비가 평소의 2배입니다' 형태의 인사이트를 제공한다.

고도화 (v2):
 - 반복 소비 탐지: 동일 카테고리 7일 내 3회 이상 또는 횟수 급증
 - 단건 고액 탐지: 단일 거래가 월 평균 총지출의 일정 비율 이상
 - 체감 소비 감지: 횟수 + 금액 혼합 점수 기반
 - 단계별 심각도: 정상 / 주의 / 과소비 가능성
"""
from __future__ import annotations

from collections import defaultdict
from datetime import date, datetime, timedelta
from typing import Any

from app.features.base import FeatureExecutorBase
from app.schemas import ChatbotFeatureExecuteRequest, ChatbotFeatureExecuteResponse

FEATURE_CODE = "SPENDING_PATTERN"

# ── 카테고리 정의 ──────────────────────────────────────────────────────────────
_CATEGORIES: list[tuple[str, list[str]]] = [
    ("식비",    ["식당", "음식", "카페", "편의점", "배달", "마트", "식비", "생활비", "생활비출금", "쿠팡이츠", "배민",
                 "요기요", "점심", "저녁", "아침", "베이커리", "분식", "치킨", "피자", "햄버거",
                 "스타벅스", "투썸", "할리스", "이디야", "커피빈", "폴바셋"]),
    ("교통",    ["교통", "버스", "지하철", "택시", "카카오택시", "주유", "기름", "ktx",
                 "고속버스", "톨", "주차", "철도"]),
    ("쇼핑",    ["쇼핑", "의류", "패션", "신발", "백화점", "아울렛", "온라인쇼핑", "쿠팡",
                 "11번가", "지마켓", "옥션", "위메프", "티몬", "네이버쇼핑",
                 "무신사", "musinsa", "지그재그", "에이블리", "브랜디", "ably",
                 "올리브영", "다이소", "이케아"]),
    ("의료",    ["병원", "의원", "약국", "한의원", "치과", "안과", "의료", "건강검진", "약값"]),
    ("공과금",  ["전기", "수도", "가스", "통신", "인터넷", "핸드폰", "휴대폰", "요금", "관리비"]),
    ("문화/여가", ["영화", "ott", "넷플릭스", "유튜브", "게임", "여행", "숙박", "호텔",
                  "헬스", "운동", "피트니스", "스포츠", "독서", "책",
                  "콘서트", "공연", "티켓", "interpark", "인터파크", "예스24", "yes24",
                  "멜론", "스포티파이", "축제", "뮤지컬", "연극"]),
    ("금융",    ["보험", "대출", "이자", "연금", "증권", "투자", "펀드"]),
]
_CATEGORY_NAMES = [c[0] for c in _CATEGORIES]

# 이벤트성 카테고리 (단건 고액 기준 완화)
_EVENT_CATEGORIES = {"문화/여가", "여행"}

# ── 탐지 파라미터 ──────────────────────────────────────────────────────────────
_ALERT_RATIO = 1.5          # 총액 급증 기준 배수
_MIN_AMOUNT_FOR_ALERT = 10_000

# 단건 고액: 월 평균 총지출 대비 비율
_SINGLE_LARGE_RATIO = 0.25          # 일반 카테고리: 25% 이상
_SINGLE_LARGE_RATIO_EVENT = 0.15    # 이벤트 카테고리: 15% 이상
_SINGLE_LARGE_MIN = 50_000          # 최소 금액 (5만 원 미만은 무시)

# 반복 소비: 7일 내 동일 카테고리 N회 이상
_REPEAT_WINDOW_DAYS = 7
_REPEAT_COUNT_THRESHOLD = 3

# 반복 소비: 이번 달 횟수 vs 직전 달 평균 횟수 비율
_REPEAT_COUNT_RATIO = 2.0

# 생활 패턴 탐지
_LIFESTYLE_CATEGORIES = {"식비"}   # 카페·편의점·배달 등 포함된 카테고리
_DAILY_REPEAT_THRESHOLD = 2        # 하루 동일 카테고리 N회 이상
_WEEKLY_REPEAT_THRESHOLD = 5       # 한 주(월~일) 동일 카테고리 N회 이상
_LIFESTYLE_CONC_RATIO = 1.5        # 생활비 거래 횟수 비중: 직전 달 대비 N배 이상

# 심각도 레이블
_SEVERITY_NORMAL = "정상"
_SEVERITY_CAUTION = "주의"
_SEVERITY_DANGER = "과소비 가능성"
_SEVERITY_LIFESTYLE = "생활 소비 증가"  # 인사이트 전용 (종합 판정 미반영)

_BEHAVIOR_KEYWORDS: dict[str, list[str]] = {
    "배달": ["배달", "배달앱", "배민", "쿠팡이츠", "요기요", "시켜먹", "주문"],
    "카페": ["카페", "커피", "스타벅스", "아메리카노", "라떼"],
    "편의점": ["편의점", "gs25", "씨유", "세븐일레븐", "미니스톱"],
    "쇼핑": ["쇼핑", "무신사", "쿠팡", "온라인", "옷", "구매"],
    "구독": ["구독", "넷플릭스", "유튜브", "ott", "멤버십", "월정액"],
}

_BEHAVIOR_CAUSES: dict[str, list[str]] = {
    "배달": ["피로·귀찮음으로 인한 조리 회피", "앱 할인/프로모션으로 인한 반복 주문"],
    "카페": ["업무 중 카페인 루틴화", "휴식·작업 공간으로 카페 이용"],
    "편의점": ["식사 대체 또는 간식 소비 빈도 증가", "이동 중 즉시 구매 패턴"],
    "쇼핑": ["스트레스성 충동 구매", "온라인 추천 노출에 따른 반복 구매"],
    "구독": ["미사용 구독 자동 결제", "구독 서비스 누적으로 고정비 증가"],
}

_BEHAVIOR_SAVING: dict[str, tuple[int, int]] = {
    "배달": (50_000, 120_000),
    "카페": (30_000, 60_000),
    "편의점": (20_000, 40_000),
    "쇼핑": (40_000, 100_000),
    "구독": (15_000, 30_000),
}

_BEHAVIOR_ACTIONS: dict[str, list[str]] = {
    "배달": ["배달 주문을 주 2회 이하로 제한", "주문 전 10분 대기 규칙 적용", "주 1회 장보기로 대체 식사 준비"],
    "카페": ["카페 방문을 주 3회 이하로 제한", "텀블러·사내 커피 등 대체 루틴 만들기"],
    "편의점": ["구매 목록을 정한 뒤 방문", "식사 대체 편의점 이용을 주 2회 이하로 제한"],
    "쇼핑": ["구매 전 24시간 대기 규칙 적용", "월 쇼핑 예산 상한 설정"],
    "구독": ["월 1회 미만 이용 구독 해지", "갱신일 전 알림 등록"],
}


class SpendingPatternAgent(FeatureExecutorBase):
    """월별 지출 패턴을 분석하고 이상 지출 항목을 경고한다."""

    def execute(self, request: ChatbotFeatureExecuteRequest) -> ChatbotFeatureExecuteResponse:
        customer_no = request.customer_no or "1"
        behavior_hints = self._extract_behavior_hints(request.query or "")
        today = date.today()

        this_month_start = today.replace(day=1)
        two_months_ago = (this_month_start - timedelta(days=1)).replace(day=1)
        cutoff = (two_months_ago - timedelta(days=1)).replace(day=1)

        tx_rows = self._fetch_outflow_transactions(customer_no, cutoff)

        if not tx_rows:
            if behavior_hints:
                message = self._format_behavior_only_message(behavior_hints)
                return ChatbotFeatureExecuteResponse(
                    feature_code=FEATURE_CODE,
                    status="OK",
                    message=message,
                    data=[],
                )
            return ChatbotFeatureExecuteResponse(
                feature_code=FEATURE_CODE,
                status="EMPTY",
                message="최근 3개월 출금 내역이 없어 지출 패턴을 분석할 수 없습니다.",
                data=[],
            )

        this_ym = today.strftime("%Y-%m")

        # 월별 × 카테고리 총액
        monthly_category: dict[tuple[str, str], float] = defaultdict(float)
        # 월별 × 카테고리 횟수
        monthly_category_count: dict[tuple[str, str], int] = defaultdict(int)
        # 이번 달 개별 거래 목록 (반복·단건 탐지용)
        this_month_tx: list[dict[str, Any]] = []

        for row in tx_rows:
            tx_date = self._parse_date(row.get("transaction_at") or row.get("created_at"))
            if tx_date is None:
                continue
            amount = float(row.get("amount") or 0)
            summary = (
                str(row.get("transaction_summary") or "") + " " +
                str(row.get("transaction_memo") or "")
            ).lower()
            category = self._classify_category(summary)
            ym = tx_date.strftime("%Y-%m")

            monthly_category[(ym, category)] += amount
            monthly_category_count[(ym, category)] += 1

            if ym == this_ym:
                this_month_tx.append({
                    "date": tx_date,
                    "amount": amount,
                    "category": category,
                    "summary": summary,
                })

        prev_months = sorted({ym for ym, _ in monthly_category.keys() if ym != this_ym})[-2:]

        # 이번 달 총지출 (단건 고액 기준선)
        total_this_month_amount = sum(
            v for (ym, _), v in monthly_category.items() if ym == this_ym
        )
        # 직전 달 평균 총지출
        if prev_months:
            prev_totals = [
                sum(v for (ym, _), v in monthly_category.items() if ym == pm)
                for pm in prev_months
            ]
            avg_prev_total = sum(prev_totals) / len(prev_totals)
        else:
            avg_prev_total = total_this_month_amount

        baseline_total = max(avg_prev_total, total_this_month_amount, 1.0)

        # 탐지 실행
        spike_alerts = self._detect_spike_alerts(monthly_category, this_ym, prev_months)
        repeat_alerts = self._detect_repeat_alerts(
            this_month_tx, monthly_category_count, this_ym, prev_months, today
        )
        single_large_alerts = self._detect_single_large_alerts(
            this_month_tx, baseline_total
        )
        lifestyle_insights = self._detect_lifestyle_alerts(
            this_month_tx, monthly_category_count, this_ym, prev_months
        )

        # 중복 카테고리 병합 (spike와 repeat이 같은 카테고리면 높은 심각도 유지)
        all_alerts = self._merge_alerts(spike_alerts, repeat_alerts, single_large_alerts)

        summary_by_category = self._summary_this_month(monthly_category, this_ym)
        behavior_insights = self._build_behavior_insights(behavior_hints, summary_by_category)
        overall_severity = self._overall_severity(all_alerts)

        message = self._format_message(
            all_alerts, summary_by_category, total_this_month_amount,
            this_ym, prev_months, overall_severity, lifestyle_insights, behavior_insights
        )

        return ChatbotFeatureExecuteResponse(
            feature_code=FEATURE_CODE,
            status="OK",
            message=message,
            data=[],
        )

    # ── 탐지 메서드 ──────────────────────────────────────────────────────────

    def _detect_spike_alerts(
        self,
        monthly_category: dict[tuple[str, str], float],
        this_ym: str,
        prev_months: list[str],
    ) -> list[dict[str, Any]]:
        """총액 급증 탐지 (기존 로직 + 심각도 추가)."""
        if not prev_months:
            return []

        alerts: list[dict[str, Any]] = []
        all_categories = {cat for _, cat in monthly_category.keys()}

        for category in all_categories:
            this_amount = monthly_category.get((this_ym, category), 0.0)
            if this_amount < _MIN_AMOUNT_FOR_ALERT:
                continue

            prev_amounts = [monthly_category.get((ym, category), 0.0) for ym in prev_months]
            prev_avg = sum(prev_amounts) / len(prev_amounts) if prev_amounts else 0.0

            if prev_avg < _MIN_AMOUNT_FOR_ALERT:
                if this_amount >= _MIN_AMOUNT_FOR_ALERT * 5:
                    alerts.append({
                        "category": category,
                        "this_month_amount": this_amount,
                        "prev_avg_amount": prev_avg,
                        "ratio": None,
                        "alert_type": "NEW_SPENDING",
                        "severity": _SEVERITY_CAUTION,
                        "message": (
                            f"이번 달 {category} 지출({this_amount:,.0f}원)이 새로 발생했습니다."
                        ),
                    })
                continue

            ratio = this_amount / prev_avg
            if ratio >= _ALERT_RATIO:
                severity = _SEVERITY_DANGER if ratio >= 2.0 else _SEVERITY_CAUTION
                ratio_text = f"{ratio:.1f}배" if ratio < 10 else "10배 이상"
                alerts.append({
                    "category": category,
                    "this_month_amount": this_amount,
                    "prev_avg_amount": prev_avg,
                    "ratio": round(ratio, 2),
                    "alert_type": "SPIKE",
                    "severity": severity,
                    "message": (
                        f"이번 달 {category} 지출({this_amount:,.0f}원)이 "
                        f"평소({prev_avg:,.0f}원)의 {ratio_text}입니다."
                    ),
                })

        alerts.sort(key=lambda a: (a.get("ratio") or 99), reverse=True)
        return alerts

    def _detect_repeat_alerts(
        self,
        this_month_tx: list[dict[str, Any]],
        monthly_category_count: dict[tuple[str, str], int],
        this_ym: str,
        prev_months: list[str],
        today: date,
    ) -> list[dict[str, Any]]:
        """반복 소비 탐지: 7일 내 3회 이상 또는 이번 달 횟수 급증."""
        alerts: list[dict[str, Any]] = []
        if not this_month_tx:
            return alerts

        # 7일 슬라이딩 윈도우 탐지
        window_start = today - timedelta(days=_REPEAT_WINDOW_DAYS - 1)
        window_tx = [t for t in this_month_tx if t["date"] >= window_start]

        category_window_count: dict[str, int] = defaultdict(int)
        category_window_amount: dict[str, float] = defaultdict(float)
        for t in window_tx:
            category_window_count[t["category"]] += 1
            category_window_amount[t["category"]] += t["amount"]

        triggered: set[str] = set()
        for cat, cnt in category_window_count.items():
            total_window = category_window_amount[cat]
            if cnt >= _REPEAT_COUNT_THRESHOLD and total_window >= _MIN_AMOUNT_FOR_ALERT:
                triggered.add(cat)
                alerts.append({
                    "category": cat,
                    "this_month_amount": total_window,
                    "repeat_count": cnt,
                    "window_days": _REPEAT_WINDOW_DAYS,
                    "alert_type": "REPEAT_WINDOW",
                    "severity": _SEVERITY_CAUTION,
                    "message": (
                        f"{cat} 항목에서 최근 {_REPEAT_WINDOW_DAYS}일간 "
                        f"{cnt}회 결제({total_window:,.0f}원)가 감지됐습니다."
                    ),
                })

        # 이번 달 횟수 급증 탐지 (7일 윈도우 미탐지 카테고리만)
        if prev_months:
            all_cats = {cat for (ym, cat) in monthly_category_count.keys()}
            for cat in all_cats:
                if cat in triggered:
                    continue
                cnt_this = monthly_category_count.get((this_ym, cat), 0)
                if cnt_this < _REPEAT_COUNT_THRESHOLD:
                    continue
                prev_counts = [
                    monthly_category_count.get((ym, cat), 0) for ym in prev_months
                ]
                prev_avg_cnt = sum(prev_counts) / len(prev_counts)
                if prev_avg_cnt < 1:
                    continue
                count_ratio = cnt_this / prev_avg_cnt
                if count_ratio >= _REPEAT_COUNT_RATIO:
                    this_amount = sum(
                        t["amount"] for t in this_month_tx if t["category"] == cat
                    )
                    alerts.append({
                        "category": cat,
                        "this_month_amount": this_amount,
                        "repeat_count": cnt_this,
                        "prev_avg_count": round(prev_avg_cnt, 1),
                        "count_ratio": round(count_ratio, 2),
                        "alert_type": "REPEAT_COUNT",
                        "severity": _SEVERITY_CAUTION,
                        "message": (
                            f"이번 달 {cat} 결제 횟수({cnt_this}회)가 "
                            f"평소({prev_avg_cnt:.1f}회)의 {count_ratio:.1f}배입니다."
                        ),
                    })

        return alerts

    def _detect_single_large_alerts(
        self,
        this_month_tx: list[dict[str, Any]],
        baseline_total: float,
    ) -> list[dict[str, Any]]:
        """단건 고액 탐지: 단일 거래가 월 평균 총지출의 일정 비율 이상."""
        alerts: list[dict[str, Any]] = []
        seen: set[str] = set()  # 카테고리당 최대 1건

        for t in sorted(this_month_tx, key=lambda x: x["amount"], reverse=True):
            cat = t["category"]
            amount = t["amount"]
            if amount < _SINGLE_LARGE_MIN:
                continue

            threshold_ratio = (
                _SINGLE_LARGE_RATIO_EVENT if cat in _EVENT_CATEGORIES
                else _SINGLE_LARGE_RATIO
            )
            if amount < baseline_total * threshold_ratio:
                continue
            if cat in seen:
                continue

            seen.add(cat)
            pct = amount / baseline_total * 100
            severity = _SEVERITY_DANGER if pct >= 40 else _SEVERITY_CAUTION
            category_label = "이벤트성" if cat in _EVENT_CATEGORIES else "고액"
            alerts.append({
                "category": cat,
                "single_amount": amount,
                "baseline_total": baseline_total,
                "pct_of_monthly": round(pct, 1),
                "alert_type": "SINGLE_LARGE",
                "severity": severity,
                "message": (
                    f"{cat} 항목에서 단건 {category_label} 결제 {amount:,.0f}원이 감지됐습니다. "
                    f"(월 평균 지출의 {pct:.0f}%)"
                ),
            })

        return alerts

    def _merge_alerts(
        self,
        spike: list[dict[str, Any]],
        repeat: list[dict[str, Any]],
        single: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        """같은 카테고리 중복 시 severity가 높은 것 우선, 나머지는 append."""
        severity_rank = {_SEVERITY_DANGER: 2, _SEVERITY_CAUTION: 1, _SEVERITY_NORMAL: 0}
        merged: dict[str, dict[str, Any]] = {}

        for alert in spike + repeat + single:
            cat = alert["category"]
            if cat not in merged:
                merged[cat] = alert
            else:
                existing_rank = severity_rank.get(merged[cat].get("severity", ""), 0)
                new_rank = severity_rank.get(alert.get("severity", ""), 0)
                if new_rank > existing_rank:
                    # 기존은 supplementary message로 보존
                    alert["supplementary"] = merged[cat].get("message")
                    merged[cat] = alert
                else:
                    # 낮은 severity는 supplementary로 추가
                    merged[cat].setdefault("supplementary_list", [])
                    merged[cat]["supplementary_list"].append(alert.get("message", ""))

        result = list(merged.values())
        severity_order = {_SEVERITY_DANGER: 0, _SEVERITY_CAUTION: 1, _SEVERITY_NORMAL: 2}
        result.sort(key=lambda a: severity_order.get(a.get("severity", ""), 9))
        return result

    def _overall_severity(self, alerts: list[dict[str, Any]]) -> str:
        if any(a.get("severity") == _SEVERITY_DANGER for a in alerts):
            return _SEVERITY_DANGER
        if any(a.get("severity") == _SEVERITY_CAUTION for a in alerts):
            return _SEVERITY_CAUTION
        return _SEVERITY_NORMAL

    def _detect_lifestyle_alerts(
        self,
        this_month_tx: list[dict[str, Any]],
        monthly_category_count: dict[tuple[str, str], int],
        this_ym: str,
        prev_months: list[str],
    ) -> list[dict[str, Any]]:
        """생활 패턴 기반 소비 탐지 (인사이트 전용, 종합 판정 미반영).

        탐지 항목:
          LIFESTYLE_DAILY    - 하루에 동일 카테고리 2회 이상
          LIFESTYLE_WEEKLY   - 한 주(월~일)에 동일 카테고리 5회 이상
          LIFESTYLE_CONC     - 생활비 카테고리 거래 비중이 직전 달 대비 1.5배 이상 증가
        """
        insights: list[dict[str, Any]] = []
        if not this_month_tx:
            return insights

        # ── 하루 2회+ ───────────────────────────────────────────────────────
        daily_count: dict[tuple[date, str], int] = defaultdict(int)
        daily_amount: dict[tuple[date, str], float] = defaultdict(float)
        for t in this_month_tx:
            daily_count[(t["date"], t["category"])] += 1
            daily_amount[(t["date"], t["category"])] += t["amount"]

        daily_seen: set[str] = set()
        for (d, cat), cnt in daily_count.items():
            if cnt >= _DAILY_REPEAT_THRESHOLD and cat not in daily_seen:
                daily_seen.add(cat)
                amt = daily_amount[(d, cat)]
                insights.append({
                    "category": cat,
                    "alert_type": "LIFESTYLE_DAILY",
                    "severity": _SEVERITY_LIFESTYLE,
                    "date": d.strftime("%m/%d"),
                    "count": cnt,
                    "amount": amt,
                    "message": (
                        f"{d.month}/{d.day}에 {cat} 항목이 {cnt}회 결제됐습니다. "
                        f"({amt:,.0f}원)"
                    ),
                })

        # ── 주 5회+ (달력 주 기준: 월요일 시작) ─────────────────────────────
        week_count: dict[tuple[str, str], int] = defaultdict(int)
        week_amount: dict[tuple[str, str], float] = defaultdict(float)
        for t in this_month_tx:
            # isocalendar: (year, week_number, weekday)
            iso = t["date"].isocalendar()
            week_key = f"{iso[0]}-W{iso[1]:02d}"
            week_count[(week_key, t["category"])] += 1
            week_amount[(week_key, t["category"])] += t["amount"]

        weekly_seen: set[str] = set()
        for (wk, cat), cnt in week_count.items():
            if cnt >= _WEEKLY_REPEAT_THRESHOLD and cat not in weekly_seen:
                weekly_seen.add(cat)
                amt = week_amount[(wk, cat)]
                insights.append({
                    "category": cat,
                    "alert_type": "LIFESTYLE_WEEKLY",
                    "severity": _SEVERITY_LIFESTYLE,
                    "week": wk,
                    "count": cnt,
                    "amount": amt,
                    "message": (
                        f"{cat} 항목이 {wk} 한 주 동안 {cnt}회 결제됐습니다. "
                        f"({amt:,.0f}원)"
                    ),
                })

        # ── 생활비 카테고리 거래 비중 급증 ──────────────────────────────────
        if prev_months:
            total_this_cnt = sum(
                monthly_category_count.get((this_ym, cat), 0)
                for cat in {c for _, c in monthly_category_count.keys()}
            )
            lifestyle_this_cnt = sum(
                monthly_category_count.get((this_ym, cat), 0)
                for cat in _LIFESTYLE_CATEGORIES
            )
            this_ratio = lifestyle_this_cnt / total_this_cnt if total_this_cnt else 0

            prev_ratios = []
            for pm in prev_months:
                total_pm = sum(
                    monthly_category_count.get((pm, cat), 0)
                    for cat in {c for _, c in monthly_category_count.keys()}
                )
                lifestyle_pm = sum(
                    monthly_category_count.get((pm, cat), 0)
                    for cat in _LIFESTYLE_CATEGORIES
                )
                if total_pm:
                    prev_ratios.append(lifestyle_pm / total_pm)

            if prev_ratios and lifestyle_this_cnt >= 3:
                prev_avg_ratio = sum(prev_ratios) / len(prev_ratios)
                if prev_avg_ratio > 0 and this_ratio >= prev_avg_ratio * _LIFESTYLE_CONC_RATIO:
                    conc_ratio = this_ratio / prev_avg_ratio
                    insights.append({
                        "category": "식비",
                        "alert_type": "LIFESTYLE_CONC",
                        "severity": _SEVERITY_LIFESTYLE,
                        "this_ratio": round(this_ratio * 100, 1),
                        "prev_ratio": round(prev_avg_ratio * 100, 1),
                        "conc_ratio": round(conc_ratio, 2),
                        "message": (
                            f"생활비(식비·카페·배달) 관련 결제 비중이 "
                            f"이번 달 {this_ratio*100:.0f}%로, "
                            f"평소({prev_avg_ratio*100:.0f}%)보다 {conc_ratio:.1f}배 높아졌습니다."
                        ),
                    })

        return insights

    # ── 포맷 ─────────────────────────────────────────────────────────────────

    def _format_message(
        self,
        alerts: list[dict[str, Any]],
        summary: dict[str, float],
        total: float,
        this_ym: str,
        prev_months: list[str],
        overall_severity: str,
        lifestyle_insights: list[dict[str, Any]] | None = None,
        behavior_insights: list[dict[str, Any]] | None = None,
    ) -> str:
        lines = ["[지출 패턴 분석]"]
        lines.append(f"분석 기준: {this_ym} (이번 달) vs 직전 {len(prev_months)}개월 평균\n")

        # 종합 판정 (+ 생활 소비 증가 부기)
        severity_icon = {"정상": "✅", "주의": "⚠", "과소비 가능성": "🔴"}.get(overall_severity, "")
        lifestyle_suffix = " / 생활 소비 증가 감지" if lifestyle_insights or behavior_insights else ""
        lines.append(f"종합 판정: {severity_icon} {overall_severity}{lifestyle_suffix}\n")

        if alerts:
            lines.append("감지된 지출 패턴:")
            for a in alerts[:6]:
                sev_icon = {"정상": "", "주의": "⚠ ", "과소비 가능성": "🔴 "}.get(
                    a.get("severity", ""), ""
                )
                lines.append(f"  • {sev_icon}{a['message']}")
                for sup in a.get("supplementary_list", []):
                    lines.append(f"    └ {sup}")
        else:
            if behavior_insights:
                lines.append("거래내역 기준 급증은 크지 않지만, 입력하신 소비 항목에서 관리 포인트가 있습니다.")
            else:
                lines.append("이번 달 특이한 지출 패턴이 감지되지 않았습니다.")

        # 생활 패턴 인사이트 (별도 섹션)
        if lifestyle_insights:
            lines.append("\n📌 생활 패턴 인사이트:")
            for ins in lifestyle_insights[:4]:
                lines.append(f"  • {ins['message']}")

        if behavior_insights:
            lines.append("\n원인 분석 및 절약 제안:")
            for insight in behavior_insights[:3]:
                lines.append(f"  • {insight['message']}")
                for cause in insight["causes"][:2]:
                    lines.append(f"    - 원인 가능성: {cause}")
                lines.append(f"    - 개선 방안: {insight['action']}")
                lines.append(f"    - 다음 달 목표: {insight['goal']}")

        if summary:
            lines.append("\n이번 달 카테고리별 지출:")
            for cat, amt in list(summary.items())[:6]:
                lines.append(f"  • {cat}: {amt:,.0f}원")
            lines.append(f"  합계: {total:,.0f}원")

        return "\n".join(lines)

    # ── 공통 유틸 ─────────────────────────────────────────────────────────────

    def _fetch_outflow_transactions(self, customer_no: str, cutoff: date) -> list[dict[str, Any]]:
        accounts = self._rows(
            "SELECT account_id FROM deposit_accounts WHERE customer_id = :cno",
            {"cno": customer_no},
        )
        if not accounts:
            return []

        id_list = ",".join(str(a["account_id"]) for a in accounts)
        own_ids = {int(a["account_id"]) for a in accounts}

        rows = self._rows(
            f"""
            SELECT t.transaction_type,
                   t.direction_type,
                   t.amount,
                   t.transaction_summary,
                   t.transaction_memo,
                   t.transaction_at,
                   t.created_at,
                   t.counterparty_account_id,
                   t.counterparty_account_no,
                   t.status
              FROM deposit_transactions t
             WHERE t.account_id IN ({id_list})
               AND COALESCE(t.transaction_at, t.created_at) >= :cutoff
               AND t.direction_type = 'OUT'
             ORDER BY COALESCE(t.transaction_at, t.created_at)
            """,
            {"cutoff": cutoff.strftime("%Y-%m-%d")},
        )

        filtered = []
        for r in rows:
            if str(r.get("status") or "").upper() not in ("", "SUCCESS", "COMPLETED", "NORMAL"):
                continue
            cp_id = r.get("counterparty_account_id")
            if cp_id is not None and int(cp_id) in own_ids:
                continue
            filtered.append(r)
        return filtered

    def _classify_category(self, text: str) -> str:
        for category_name, keywords in _CATEGORIES:
            if any(kw in text for kw in keywords):
                return category_name
        return "기타"

    def _extract_behavior_hints(self, query: str) -> list[str]:
        text = query.lower()
        hints = []
        for category, keywords in _BEHAVIOR_KEYWORDS.items():
            if any(keyword in text for keyword in keywords):
                hints.append(category)
        return hints

    def _build_behavior_insights(
        self,
        behavior_hints: list[str],
        summary_by_category: dict[str, float] | None,
    ) -> list[dict[str, Any]]:
        insights = []
        if not behavior_hints:
            return insights

        food_amount = 0.0
        if summary_by_category:
            food_amount = float(summary_by_category.get("식비") or summary_by_category.get("기타") or 0)

        for behavior in behavior_hints:
            saving_low, saving_high = _BEHAVIOR_SAVING.get(behavior, (30_000, 70_000))
            saving_estimate = int((saving_low + saving_high) / 2)
            if food_amount > 0:
                saving_estimate = min(max(saving_estimate, int(food_amount * 0.05)), int(food_amount * 0.25))

            action = (_BEHAVIOR_ACTIONS.get(behavior) or ["반복 소비 횟수를 줄이고 주간 예산을 설정"])[0]
            goal_amount = max(10_000, int(saving_estimate * 3))
            insights.append({
                "behavior": behavior,
                "causes": _BEHAVIOR_CAUSES.get(behavior, ["반복 소비 루틴화 가능성"]),
                "action": action,
                "goal": f"{behavior} 관련 지출을 다음 달 {goal_amount:,.0f}원 이하로 관리",
                "message": (
                    f"{behavior} 사용 증가가 체감 원인으로 보입니다. "
                    f"주 1~2회만 줄여도 월 약 {saving_estimate:,.0f}원 절약 여지가 있습니다."
                ),
            })
        return insights

    def _format_behavior_only_message(self, behavior_hints: list[str]) -> str:
        insights = self._build_behavior_insights(behavior_hints, None)
        lines = ["[지출 패턴 분석]"]
        lines.append("최근 3개월 출금 내역이 충분하지 않아 거래 기반 증감률은 계산하지 못했습니다.\n")
        lines.append("입력하신 소비 습관 기준으로 관리 포인트를 제안드립니다.")
        lines.append("\n원인 분석 및 절약 제안:")
        for insight in insights[:3]:
            lines.append(f"  • {insight['message']}")
            for cause in insight["causes"][:2]:
                lines.append(f"    - 원인 가능성: {cause}")
            lines.append(f"    - 개선 방안: {insight['action']}")
            lines.append(f"    - 다음 달 목표: {insight['goal']}")
        return "\n".join(lines)

    def _summary_this_month(
        self,
        monthly_category: dict[tuple[str, str], float],
        this_ym: str,
    ) -> dict[str, float]:
        result: dict[str, float] = {}
        for (ym, cat), amt in monthly_category.items():
            if ym == this_ym and amt > 0:
                result[cat] = result.get(cat, 0.0) + amt
        return dict(sorted(result.items(), key=lambda x: x[1], reverse=True))

    def _parse_date(self, value: Any) -> date | None:
        if value is None:
            return None
        if isinstance(value, datetime):
            return value.date()
        if isinstance(value, date):
            return value
        text_value = str(value).strip()
        for fmt in ("%Y-%m-%d %H:%M:%S%z", "%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d"):
            try:
                return datetime.strptime(text_value[:19], fmt[:len(text_value[:19])]).date()
            except ValueError:
                continue
        try:
            return datetime.fromisoformat(text_value).date()
        except (ValueError, TypeError):
            return None
