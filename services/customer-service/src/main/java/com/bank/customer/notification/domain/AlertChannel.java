package com.bank.customer.notification.domain;

/** 입금통보를 받을 곳. */
public enum AlertChannel {

    /** 앱 알림함. 고객번호만으로 보낼 수 있어 연락처가 따로 필요 없다. */
    PUSH,

    /** 문자. 휴대폰번호가 있어야 한다. */
    SMS,

    /** 이메일. 주소가 있어야 한다. */
    EMAIL;

    /** 연락처를 따로 받아야 하는 채널인가. PUSH 는 고객 자신이 곧 목적지다. */
    public boolean requiresContact() {
        return this != PUSH;
    }
}
