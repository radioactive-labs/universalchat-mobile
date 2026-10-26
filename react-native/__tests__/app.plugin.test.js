const { atLeast } = require('../app.plugin')

test('raises a floor below what the SDKs need', () => {
  expect(atLeast('15.1', '16.0')).toBe('16.0')
  expect(atLeast('21', 24)).toBe(24)
  expect(atLeast(undefined, '16.0')).toBe('16.0')
})

test('keeps a floor already above it', () => {
  expect(atLeast('17.0', '16.0')).toBe('17.0')
  expect(atLeast('26', 24)).toBe('26')
})
