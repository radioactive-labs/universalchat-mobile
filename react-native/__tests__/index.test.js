const calls = []
const native = new Proxy({}, {
  get: (_, method) => (...args) => {
    calls.push([method, ...args])
    return Promise.resolve(method === 'login' ? 'user-7' : true)
  }
})

const listeners = {}
jest.mock('react-native', () => ({
  NativeModules: { UniversalChat: native },
  NativeEventEmitter: class {
    addListener(name, handler) {
      listeners[name] = handler
      return { remove: () => { delete listeners[name] } }
    }
  }
}), { virtual: true })

const UniversalChat = require('../src/index')

beforeEach(() => { calls.length = 0 })

test('configure passes the server and the deployment as text', async () => {
  await UniversalChat.configure({ serverUrl: 'https://acme.example.com', deploymentId: 42 })

  expect(calls).toEqual([['configure', 'https://acme.example.com', '42']])
})

test('login resolves with the user id', async () => {
  await expect(UniversalChat.login('signed.jwt')).resolves.toBe('user-7')
  expect(calls).toEqual([['login', 'signed.jwt']])
})

test('attributes keep their types', async () => {
  await UniversalChat.setAttributes({ plan: 'pro', seats: 3, trial: false, old: null })

  expect(calls).toEqual([['setAttributes', { plan: 'pro', seats: 3, trial: false, old: null }]])
})

test('push data is handed over as strings, with the notification text', async () => {
  await UniversalChat.handlePush({ ucb: 1, ucb_deployment_id: 42, empty: null }, { title: 'Sam', body: 'Hi' })

  expect(calls).toEqual([['handlePush', { ucb: '1', ucb_deployment_id: '42', empty: '' }, 'Sam', 'Hi']])
})

test('a push without notification text sends nulls', async () => {
  await UniversalChat.handlePush({ ucb: '1' })

  expect(calls).toEqual([['handlePush', { ucb: '1' }, null, null]])
})

test('the default export and the named export are the same', () => {
  expect(UniversalChat.default).toBe(UniversalChat)
  expect(UniversalChat.UniversalChat).toBe(UniversalChat)
})

test('the link handler sends its prefixes and hears links back', async () => {
  const opened = []
  await UniversalChat.setLinkHandler(['myapp://'], (url) => opened.push(url))

  expect(calls).toEqual([['setLinkPrefixes', ['myapp://']]])
  listeners.UniversalChatLink({ url: 'myapp://orders/1001' })
  expect(opened).toEqual(['myapp://orders/1001'])
})

test('no prefixes stops taking links', async () => {
  await UniversalChat.setLinkHandler(['myapp://'], () => {})
  calls.length = 0

  await UniversalChat.setLinkHandler()

  expect(calls).toEqual([['setLinkPrefixes', []]])
  expect(listeners.UniversalChatLink).toBeUndefined()
})
