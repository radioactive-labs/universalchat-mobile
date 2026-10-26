import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:universal_chat/universal_chat.dart';

// Pass these with --dart-define. The server URL and deployment ID are in the
// deployment's App setup panel; a user token comes from your own server.
const serverUrl = String.fromEnvironment('SERVER_URL');
const deploymentId = String.fromEnvironment('DEPLOYMENT_ID');
const userToken = String.fromEnvironment('USER_TOKEN');

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await UniversalChat.configure(serverUrl: serverUrl, deploymentId: deploymentId);
  await UniversalChat.setAttributes({'screen': 'home', 'app_version': '1.0.0'});
  runApp(const ExampleApp());
}

class ExampleApp extends StatefulWidget {
  const ExampleApp({super.key});

  @override
  State<ExampleApp> createState() => _ExampleAppState();
}

class _ExampleAppState extends State<ExampleApp> {
  String _status = 'Anonymous visitor';

  Future<void> _signIn() async {
    try {
      final id = await UniversalChat.login(userToken);
      setState(() => _status = 'Signed in as $id');
    } on PlatformException catch (error) {
      setState(() => _status = 'Sign-in refused: ${error.message}');
    }
  }

  Future<void> _signOut() async {
    await UniversalChat.logout();
    setState(() => _status = 'Anonymous visitor');
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('Universal Chat example')),
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            spacing: 12,
            children: [
              Text(_status),
              FilledButton(onPressed: UniversalChat.present, child: const Text('Open chat')),
              if (userToken.isNotEmpty) OutlinedButton(onPressed: _signIn, child: const Text('Sign in')),
              TextButton(onPressed: _signOut, child: const Text('Sign out')),
            ],
          ),
        ),
      ),
    );
  }
}
