const { withGradleProperties, withPodfileProperties } = require('@expo/config-plugins')

// The chat SDKs need iOS 16 and Android 7.0 (API 24). Raises the app's
// floors to those, and leaves a higher one alone.
const IOS_MINIMUM = '16.0'
const ANDROID_MINIMUM = 24

function atLeast(current, minimum) {
  return current && parseFloat(current) >= parseFloat(minimum) ? current : minimum
}

function withUniversalChat(config) {
  config = withPodfileProperties(config, (podfile) => {
    podfile.modResults['ios.deploymentTarget'] = atLeast(podfile.modResults['ios.deploymentTarget'], IOS_MINIMUM)
    return podfile
  })

  return withGradleProperties(config, (gradle) => {
    const key = 'android.minSdkVersion'
    const entry = gradle.modResults.find((item) => item.type === 'property' && item.key === key)
    const value = String(atLeast(entry?.value, ANDROID_MINIMUM))
    if (entry) {
      entry.value = value
    } else {
      gradle.modResults.push({ type: 'property', key, value })
    }
    return gradle
  })
}

module.exports = withUniversalChat
module.exports.atLeast = atLeast
