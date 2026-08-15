"""조사 스코프 — 이 사건에서 에이전트가 볼 수 있는 범위.

**무엇이 문제였나.** 도구가 볼 대상을 정하는 규칙이 이렇게 생겼었다.

    ident = state.alert.account if tool in ACCOUNT_TOOLS else state.alert.customer_id

이 한 줄이 `graph.py` 와 `api.py` **두 곳에 각각** 있었다. 지금 값은 맞다 — 사건의
알림에서 가져오므로 플래너가 다른 고객을 지목할 방법이 없다. 그런데 그것은 *규칙이
강제돼서*가 아니라 *두 줄이 우연히 같아서*다.

그 차이가 드러나는 순간이 온다.

- 도구 호출을 진짜 tool-calling 으로 바꾸면(모델이 인자까지 만든다) 이 줄이
  없어지고, 없어져도 아무 테스트가 실패하지 않는다.
- ``tools.py`` 의 함수들은 지금도 아무 식별자로나 부를 수 있다. ``get_auth_events``
  는 실연결 시 내부 API 를 실제로 친다.
- 한쪽 루프만 고치면 다른 쪽은 조용히 예전 규칙으로 남는다.

**그래서 범위를 값으로 만든다.** 호출부가 식별자를 *넘기는* 대신 스코프에게
*물어본다*. 넘길 수 없으면 잘못 넘길 수도 없다.

리드미가 "identity, permissions … constrain what they can **see** and do" 라고
말한다. `do` 는 HITL·RBAC·fail-closed 가 잡고 있었지만 `see` 는 잡는 것이 없었다.
여기가 그 자리다.
"""

from __future__ import annotations

from dataclasses import dataclass, field

#: 고객이 아니라 **계좌**를 보는 도구들. 나머지는 고객 단위로 조회한다.
ACCOUNT_TOOLS = frozenset({"get_device_fingerprint", "get_related_accounts"})


class OutOfScopeError(PermissionError):
    """이 사건에서 볼 수 없는 대상을 요구했다.

    ``PermissionError`` 를 상속한다. 조회 실패(ValueError)와 구분돼야 한다 —
    앞은 "없다" 이고 이쪽은 "봐서는 안 된다" 라서, 감사에서 뜻이 다르다.
    """


@dataclass(frozen=True)
class CaseScope:
    """사건 하나가 열어 주는 범위. 만든 뒤에는 넓힐 수 없다(frozen).

    Attributes:
        customer_id: 이 사건의 고객
        accounts: 볼 수 있는 계좌들. 알림 계좌에서 시작한다.
    """

    customer_id: str
    accounts: frozenset[str] = field(default_factory=frozenset)

    @classmethod
    def of(cls, alert) -> CaseScope:
        """알림에서 스코프를 연다. **여기가 범위가 정해지는 유일한 지점이다.**"""
        account = getattr(alert, "account", None)
        return cls(
            customer_id=str(getattr(alert, "customer_id", "") or ""),
            accounts=frozenset({str(account)} if account else set()),
        )

    def subject_for(self, tool: str) -> str:
        """이 도구가 볼 대상. 호출부는 식별자를 만들지 않고 여기에 물어본다.

        넘길 수 없으면 잘못 넘길 수도 없다 — 이것이 이 함수의 존재 이유다.
        """
        if tool in ACCOUNT_TOOLS:
            if not self.accounts:
                raise OutOfScopeError(f"{tool}: 이 사건에 계좌가 없다")
            return sorted(self.accounts)[0]
        if not self.customer_id:
            raise OutOfScopeError(f"{tool}: 이 사건에 고객이 없다")
        return self.customer_id

    def check(self, tool: str, subject: str) -> str:
        """명시적으로 넘어온 대상이 범위 안인지 본다.

        도구를 직접 부르는 경로(스크립트·후속 코드)를 위한 이중 방어다. 정상
        경로는 :meth:`subject_for` 를 쓰므로 여기까지 오지 않는다.
        """
        allowed = self.accounts if tool in ACCOUNT_TOOLS else {self.customer_id}
        if str(subject) not in allowed:
            raise OutOfScopeError(
                f"{tool}: {subject!r} 는 이 사건의 범위 밖이다 (허용: {sorted(allowed)})"
            )
        return str(subject)


__all__ = ["ACCOUNT_TOOLS", "CaseScope", "OutOfScopeError"]
