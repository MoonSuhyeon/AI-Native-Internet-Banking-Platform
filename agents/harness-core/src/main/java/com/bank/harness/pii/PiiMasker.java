package com.bank.harness.pii;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 프로세스 밖으로 나가는 문자열에서 개인식별정보를 가린다.
 *
 * <p>같은 규칙이 이미 {@code review-ai-gateway} 의 {@code PiiMaskingUtil} 에 있었지만
 * 거기 묶여 있어서 <b>도구 응답에만</b> 걸렸다. 외부 LLM 으로 나가는 프롬프트에는
 * 걸리는 곳이 없어, 프롬프트에 실린 고객 정보가 그대로 나갔다.
 *
 * <p>마스킹은 완전하지 않다. 정규식이 잡는 것만 가린다 — 주소·직장명처럼 형태가
 * 일정하지 않은 것은 통과한다. 그래서 이것은 <b>유출을 줄이는 장치</b>이지
 * "외부 LLM 을 써도 된다" 는 근거가 아니다. 정식 경로는 내부 추론이다.
 *
 * <p>가리는 것
 * <ul>
 *   <li>주민등록번호 {@code 000000-0000000} → {@code [RRN]}</li>
 *   <li>계좌번호 {@code 000-00-000000} → {@code [ACCT]}</li>
 *   <li>휴대전화 {@code 010-0000-0000} → {@code [PHONE]}</li>
 *   <li>이메일 → {@code [EMAIL]}</li>
 *   <li>한글 이름 2~4자 뒤에 '님' 또는 '씨' → {@code [NAME]}</li>
 * </ul>
 *
 * <p><b>정규식이 잡을 수 없는 것.</b> {@code customerId="9111"} 은 숫자 네 자리일 뿐이라
 * 어떤 규칙에도 걸리지 않는다. 그래서 값이 아니라 <b>필드 이름</b>으로 판단하는 길을
 * 따로 둔다({@link #scrubAttribute}). 식별자는 지우지 않고 가명으로 바꾼다 — 지우면
 * "같은 고객의 실행 3건" 을 묶을 수 없어 추적을 켠 이유가 없어진다.
 *
 * <p><b>파이썬 쪽과 같은 값이 나와야 한다.</b> 같은 소금 · 같은 HMAC-SHA256 · 같은
 * 자리수를 쓴다({@code harness_core.pii.pseudonymize}). 어긋나면 같은 고객이 런타임마다
 * 다른 가명이 되어, 자바 에이전트와 파이썬 에이전트의 실행이 이어지지 않는다.
 */
public final class PiiMasker {

    private PiiMasker() {}

    private static final Pattern RRN   = Pattern.compile("\\d{6}-\\d{7}");
    private static final Pattern ACCT  = Pattern.compile("\\d{3,6}-\\d{2,6}-\\d{6,}");
    private static final Pattern PHONE = Pattern.compile("01[016789]-?\\d{3,4}-?\\d{4}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern NAME  = Pattern.compile("[가-힣]{2,4}(?=님|씨)");

    /** 추적에 실린 값이 식별자인지는 이름이 알려 준다. 파이썬 {@code _ID_KEYS} 와 같은 표다. */
    private static final Map<String, String> ID_KEYS = Map.ofEntries(
            Map.entry("customerid", "cust"),   Map.entry("customerno", "cust"),
            Map.entry("custid", "cust"),       Map.entry("custno", "cust"),
            Map.entry("accountid", "acct"),    Map.entry("accountno", "acct"),
            Map.entry("accountnumber", "acct"), Map.entry("acctno", "acct"),
            Map.entry("employeeid", "emp"),    Map.entry("staffid", "emp"),
            Map.entry("loginid", "user"),      Map.entry("userid", "user"),
            Map.entry("customername", "name"), Map.entry("holdername", "name"),
            Map.entry("employeename", "name"), Map.entry("counterpartyname", "name"),
            Map.entry("username", "name"),     Map.entry("recipientname", "name"),
            Map.entry("phonenumber", "phone"), Map.entry("phoneno", "phone"),
            Map.entry("mobileno", "phone"),
            Map.entry("emailaddress", "email"),
            Map.entry("rrn", "rrn"),           Map.entry("residentregistrationnumber", "rrn"),
            Map.entry("birthdate", "birth"),   Map.entry("dateofbirth", "birth"));

    /**
     * 소금 미설정 시 쓰는 임의 값.
     *
     * <p>빈 소금으로 고정하면 알려진 소금이라 가명처리를 하지 않은 것과 같아진다.
     * 임의 값이면 되돌리기는 오히려 어렵지만 <b>묶이지 않는다</b> — 그 사실이 드러나도록
     * 아래에서 한 번 알린다.
     */
    private static final String FALLBACK_SALT = randomSalt();

    private static final AtomicBoolean FALLBACK_WARNED = new AtomicBoolean(false);

    private static String randomSalt() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 순서가 중요하다. 계좌번호를 먼저 가리면 주민번호 일부가 계좌로 잡힌다. */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = RRN.matcher(text).replaceAll("[RRN]");
        masked = ACCT.matcher(masked).replaceAll("[ACCT]");
        masked = PHONE.matcher(masked).replaceAll("[PHONE]");
        masked = EMAIL.matcher(masked).replaceAll("[EMAIL]");
        masked = NAME.matcher(masked).replaceAll("[NAME]");
        return masked;
    }

    /**
     * 추적 속성 하나를 가린다. 이름이 식별자를 뜻하면 가명으로, 아니면 마스킹으로.
     *
     * <p>가리는 판단을 부르는 쪽에 두지 않는 이유는, 속성이 늘 때마다 "여기도 가려야지"
     * 를 다시 떠올려야 하면 언젠가 한 곳이 빠지고 그 한 곳이 유출 경로로 남기 때문이다.
     *
     * @param key   속성 이름. {@code harness.customer_id} 처럼 앞에 붙은 이름은 뗀다.
     * @param value 속성 값
     */
    public static String scrubAttribute(String key, Object value) {
        if (value == null) {
            return "";
        }
        String prefix = idPrefix(key);
        return prefix != null ? pseudonymize(value.toString(), prefix) : mask(value.toString());
    }

    /** 이름이 식별자를 뜻하면 가명 접두사를, 아니면 {@code null}. 복수형도 본다. */
    public static String idPrefix(String key) {
        if (key == null) {
            return null;
        }
        int dot = key.lastIndexOf('.');
        String tail = (dot >= 0 ? key.substring(dot + 1) : key)
                .replace("_", "").replace("-", "").toLowerCase();
        String prefix = ID_KEYS.get(tail);
        if (prefix == null && tail.endsWith("s")) {
            prefix = ID_KEYS.get(tail.substring(0, tail.length() - 1));
        }
        return prefix;
    }

    /**
     * 식별자를 되돌릴 수 없는 안정된 가명으로 바꾼다.
     *
     * <p>같은 입력 → 같은 출력이므로 추적을 묶을 수 있고, 소금 없이는 되돌릴 수 없다.
     * 파이썬 {@code harness_core.pii.pseudonymize} 와 같은 값을 낸다.
     */
    public static String pseudonymize(String value, String prefix) {
        return pseudonymize(value, prefix, salt());
    }

    /**
     * 소금을 명시해 부르는 자리.
     *
     * <p>런타임 간 값이 같은지 대조하려면 소금을 고정해야 하는데, 환경변수는 프로세스
     * 안에서 바꿀 수 없다. 테스트 때문에 운영 설정을 하나 더 만드는 대신 이음매를 낸다.
     */
    static String pseudonymize(String value, String prefix, String salt) {
        if (value == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String hex = HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
            return prefix + "_" + hex.substring(0, 12);
        } catch (Exception e) {
            // 가명을 만들 수 없으면 원본을 내보내는 대신 지운다. 유출보다 공백이 낫다.
            return prefix + "_unavailable";
        }
    }

    private static String salt() {
        String configured = System.getenv("AGENT_PII_SALT");
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        if (FALLBACK_WARNED.compareAndSet(false, true)) {
            System.getLogger(PiiMasker.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "AGENT_PII_SALT 가 없어 임의 소금으로 시작한다. 가명이 프로세스마다 달라져 "
                    + "같은 고객의 실행을 묶을 수 없다 — 재기동 전후도, 파이썬 에이전트와의 "
                    + "사이도 이어지지 않는다. 운영에서는 반드시 설정할 것.");
        }
        return FALLBACK_SALT;
    }
}
