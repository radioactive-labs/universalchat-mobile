jest.mock('react-native', () => ({ NativeModules: {} }), { virtual: true })

const UniversalChat = require('../src/index')

test('says how to fix a module that is not linked', () => {
  expect(() => UniversalChat.present()).toThrow(/isn't linked/)
})
