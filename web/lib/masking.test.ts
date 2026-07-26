import { describe, it, expect } from 'vitest'
import { maskPhone, maskEmail } from './masking'

describe('maskPhone', () => {
  it('하이픈이 있는 번호의 가운데 4자리를 마스킹한다', () => {
    expect(maskPhone('010-1234-5678')).toBe('010-****-5678')
  })
  it('하이픈이 없어도 마스킹한다', () => {
    expect(maskPhone('01012345678')).toBe('010-****-5678')
  })
  it('null 은 대시(-)로 표시한다', () => {
    expect(maskPhone(null)).toBe('-')
  })
  it('형식에 맞지 않으면 원본을 유지한다', () => {
    expect(maskPhone('N/A')).toBe('N/A')
  })
})

describe('maskEmail', () => {
  it('로컬파트를 첫 글자만 남기고 마스킹한다', () => {
    expect(maskEmail('hong@axful.com')).toBe('h****@axful.com')
  })
  it('null 은 대시(-)로 표시한다', () => {
    expect(maskEmail(null)).toBe('-')
  })
})
