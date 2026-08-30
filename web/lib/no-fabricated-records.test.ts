import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, resolve, sep } from 'node:path'

/**
 * 화면이 고객 기록을 지어내지 않는지 본다.
 *
 * ## 왜 필요한가
 *
 * 예금 신규내역 화면에 가입 기록 두 줄이 하드코딩돼 있었고, 그것이 초기값이자
 * 조회 실패 시의 폴백이었다. 그래서 **로그인하지 않은 사람에게도** 계좌번호와
 * 금액이 담긴 표가 떴다.
 *
 * 실제 고객 데이터는 아니었다 — 게이트웨이가 미인증 호출을 막으므로 조회는 언제나
 * 실패했고, 화면은 그 실패를 가짜로 덮었다. 그러나 **보는 사람은 그것을 구분할 수
 * 없다.** 은행 화면에 남의 계좌번호와 거래 금액이 떠 있으면 유출로 읽힌다.
 *
 * ## 무엇을 보는가
 *
 * 화면 코드(app·components)에 계좌번호 모양의 문자열이 박혀 있는지 본다.
 * 목록·코드표 같은 참조 데이터는 lib 에 두므로 여기서는 걸리지 않는다.
 *
 * 조회가 안 되면 안 되는 대로 보여주면 된다. 빈 표가 가짜 기록보다 낫다.
 */

/** 줄 단위로 보기 위한 개행. CRLF 파일은 끝에 CR 이 남지만 검사에 영향이 없다. */
const LINE_BREAK = String.fromCharCode(10)

const WEB_ROOT = resolve(__dirname, '..')
const SCAN_DIRS = [join(WEB_ROOT, 'app'), join(WEB_ROOT, 'components')]

/** `557315-2623671` · `531089-04-274618` 같은 계좌번호 모양. */
const ACCOUNT_LITERAL = /['"`]\d{6}-\d{2}-\d{6}['"`]|['"`]\d{6}-\d{7}['"`]/g

function sourceFiles(dir: string): string[] {
  let out: string[] = []
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) {
      out = out.concat(sourceFiles(full))
    } else if (/\.tsx?$/.test(name) && !/\.(test|spec)\.tsx?$/.test(name)) {
      out.push(full)
    }
  }
  return out
}

describe('화면이 지어낸 고객 기록', () => {
  it('계좌번호를 화면 코드에 박아 두지 않는다', () => {
    const offenders: string[] = []
    for (const dir of SCAN_DIRS) {
      for (const file of sourceFiles(dir)) {
        const rel = relative(WEB_ROOT, file).split(sep).join('/')
        readFileSync(file, 'utf-8').split(LINE_BREAK).forEach((line, i) => {
          // 입력 형식 안내는 기록이 아니다. 빈 칸에 흐리게 뜨는 예시(placeholder)와
          // "형식(123456-1234567)에 맞게" 같은 안내문은 아무 계좌도 가리키지 않는다.
          if (line.includes('placeholder') || line.includes('형식')) return
          // matchAll 을 그대로 순회하면 tsconfig 의 target 에 걸린다. 배열로 받는다.
          for (const hit of Array.from(line.matchAll(ACCOUNT_LITERAL))) {
            offenders.push(`${rel}:${i + 1} → ${hit[0]}`)
          }
        })
      }
    }

    expect(
      offenders,
      '화면 코드에 계좌번호가 박혀 있다. 조회 실패를 이런 값으로 덮으면\n' +
        '로그인하지 않은 사람에게도 남의 기록처럼 보이는 표가 뜬다.\n' +
        '조회가 안 되면 빈 표와 안내 문구를 보여줄 것.\n' +
        offenders.join('\n'),
    ).toEqual([])
  })

  it('실제로 검사할 파일을 찾는다', () => {
    // 경로가 바뀌어 아무것도 안 읽으면 위 검사는 언제나 통과한다.
    const files = SCAN_DIRS.flatMap(sourceFiles)
    expect(files.length).toBeGreaterThan(20)
  })
})
