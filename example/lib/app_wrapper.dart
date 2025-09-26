import 'dart:io';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:steps_count/steps_count.dart';
import 'package:steps_count_example/home_screen.dart';
import 'package:steps_count_example/widgets/permission_screen.dart';

class AppWrapper extends StatefulWidget {
  const AppWrapper({super.key});

  @override
  State<AppWrapper> createState() => _AppWrapperState();
}

class _AppWrapperState extends State<AppWrapper> with WidgetsBindingObserver {
  bool _hasPermission = false;
  bool _isCheckingPermissions = true;
  final _stepsCounterPlugin = StepsCount();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkPermissions();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    // Check permissions when app comes back to foreground
    if (state == AppLifecycleState.resumed) {
      _checkPermissions();
    }
  }

  Future<void> _checkPermissions() async {
    try {
      if (Platform.isIOS) {
        final isAuthorized = await _stepsCounterPlugin
            .checkSingleHealthKitPermissionStatus(
              dataType: HealthDataType.stepCount,
            );

        if (mounted) {
          setState(() {
            _hasPermission = isAuthorized;
            _isCheckingPermissions = false;
          });
        }
      } else {
        final activityRecognitionStatus =
            await Permission.activityRecognition.status;
        final notificationStatus = await Permission.notification.status;

        final hasAllPermissions =
            activityRecognitionStatus.isGranted && notificationStatus.isGranted;

        if (mounted) {
          setState(() {
            _hasPermission = hasAllPermissions;
            _isCheckingPermissions = false;
          });
        }
      }
    } catch (e) {
      debugPrint('Error checking permissions: $e');
      if (mounted) {
        setState(() {
          _hasPermission = false;
          _isCheckingPermissions = false;
        });
      }
    }
  }

  void _onPermissionGranted() {
    setState(() {
      _hasPermission = true;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_isCheckingPermissions) {
      return const Scaffold(
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 16),
              Text(
                'Checking permissions...',
                style: TextStyle(fontSize: 16, color: Colors.grey),
              ),
            ],
          ),
        ),
      );
    }

    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 500),
      transitionBuilder: (Widget child, Animation<double> animation) {
        return FadeTransition(
          opacity: animation,
          child: SlideTransition(
            position:
                Tween<Offset>(
                  begin: const Offset(0.0, 0.1),
                  end: Offset.zero,
                ).animate(
                  CurvedAnimation(
                    parent: animation,
                    curve: Curves.easeOutCubic,
                  ),
                ),
            child: child,
          ),
        );
      },
      child: _hasPermission
          ? const HomeScreen(key: ValueKey('home'))
          : PermissionScreen(
              key: const ValueKey('permission'),
              onPermissionGranted: _onPermissionGranted,
            ),
    );
  }
}
