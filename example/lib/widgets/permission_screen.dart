import 'dart:io';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:steps_count/steps_count.dart';
import 'package:steps_count_example/utils/app_utils.dart';
import 'package:steps_count_example/widgets/common_button.dart';
import 'package:url_launcher/url_launcher.dart';

class PermissionScreen extends StatefulWidget {
  final VoidCallback onPermissionGranted;

  const PermissionScreen({super.key, required this.onPermissionGranted});

  @override
  State<PermissionScreen> createState() => _PermissionScreenState();
}

class _PermissionScreenState extends State<PermissionScreen>
    with TickerProviderStateMixin {
  bool _isLoading = false;
  late AnimationController _animationController;
  late Animation<double> _scaleAnimation;
  late Animation<double> _fadeAnimation;
  final _stepsCounterPlugin = StepsCount();

  @override
  void initState() {
    super.initState();
    _animationController = AnimationController(
      duration: const Duration(milliseconds: 800),
      vsync: this,
    );

    _scaleAnimation = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(parent: _animationController, curve: Curves.elasticOut),
    );

    _fadeAnimation = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(parent: _animationController, curve: Curves.easeIn),
    );

    _animationController.forward();
  }

  @override
  void dispose() {
    _animationController.dispose();
    super.dispose();
  }

  Future<bool> checkHealthKitPermission() async {
    final isAuthorized = await _stepsCounterPlugin
        .checkSingleHealthKitPermissionStatus(
          dataType: HealthDataType.stepCount,
        );
    return isAuthorized;
  }

  Future<void> _requestPermissions() async {
    setState(() => _isLoading = true);
    try {
      if (Platform.isIOS) {
        final requestResult = await _stepsCounterPlugin
            .requestHealthKitPermissions(dataTypes: [HealthDataType.stepCount]);
        final isAuthorized = await checkHealthKitPermission();
        if (!mounted) return;
        if (requestResult && isAuthorized) {
          AppUtils.showSnackBar(context, 'Permissions granted successfully!');
          widget.onPermissionGranted();
        } else if (requestResult && !isAuthorized) {
          await showHealthPermissionDialog();
          final isAuthorized = await checkHealthKitPermission();
          if (isAuthorized) widget.onPermissionGranted();
        } else {
          AppUtils.showSnackBar(context, 'Permissions denied!');
        }
      } else {
        // Request both permissions
        final Map<Permission, PermissionStatus> statuses = await [
          Permission.activityRecognition,
          Permission.notification,
        ].request();

        if (!mounted) return;

        final activityStatus = statuses[Permission.activityRecognition]!;
        final notificationStatus = statuses[Permission.notification]!;

        if (activityStatus.isPermanentlyDenied ||
            notificationStatus.isPermanentlyDenied) {
          _showPermanentlyDeniedDialog();
        } else if (activityStatus.isGranted && notificationStatus.isGranted) {
          AppUtils.showSnackBar(context, 'Permissions granted successfully!');
          widget.onPermissionGranted();
        } else {
          AppUtils.showSnackBar(
            context,
            'Some permissions were denied. Please try again.',
            color: Colors.orange,
          );
        }
      }
    } catch (e) {
      if (!mounted) return;
      AppUtils.showSnackBar(
        context,
        'Error requesting permissions: $e',
        color: Colors.red,
      );
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  void _showPermanentlyDeniedDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        title: const Row(
          children: [
            Icon(Icons.warning_amber_rounded, color: Colors.orange),
            SizedBox(width: 8),
            Text('Permission Required'),
          ],
        ),
        content: const Text(
          'Permissions have been permanently denied. '
          'Please enable them manually in the app settings to use step counting features.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () async {
              Navigator.of(context).pop();
              await openAppSettings();
            },
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.orange,
              foregroundColor: Colors.white,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            child: const Text('Open Settings'),
          ),
        ],
      ),
    );
  }

  Future<void> showHealthPermissionDialog() async {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        title: const Text('Permissions Required'),
        content: const Text(
          'To sync your steps with Apple Health:\n\n'
          '1. Open the Health app\n'
          '2. Tap your Avatar in the top right corner\n'
          '3. Go to Apps in Privacy section\n'
          '4. Find this app and enable "Turn On all"',
        ),
        actions: [
          ElevatedButton(
            onPressed: () async {
              Navigator.of(context).pop();
              await openHealthApp();
            },
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.blue,
              foregroundColor: Colors.white,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            child: const Text('Open Health App'),
          ),
        ],
      ),
    );
  }

  Future<void> openHealthApp() async {
    final Uri uri = Uri.parse("x-apple-health://");

    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    } else {
      throw "Could not open Apple Health app";
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.grey.shade50,
      body: SafeArea(
        child: AnimatedBuilder(
          animation: _animationController,
          builder: (context, child) {
            return FadeTransition(
              opacity: _fadeAnimation,
              child: ScaleTransition(
                scale: _scaleAnimation,
                child: Padding(
                  padding: const EdgeInsets.all(24.0),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      _buildHeader(),
                      const SizedBox(height: 40),
                      _buildPermissionCards(),
                      const SizedBox(height: 40),
                      _buildActionButton(),
                    ],
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildHeader() {
    return Column(
      children: [
        Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: Colors.blue.shade100,
            shape: BoxShape.circle,
            boxShadow: [
              BoxShadow(
                color: Colors.blue.withValues(alpha: 0.3),
                blurRadius: 20,
                spreadRadius: 5,
              ),
            ],
          ),
          child: Icon(
            Icons.security_rounded,
            size: 60,
            color: Colors.blue.shade600,
          ),
        ),
        const SizedBox(height: 20),
        Text(
          'Permission Required',
          style: Theme.of(context).textTheme.headlineMedium?.copyWith(
            fontWeight: FontWeight.bold,
            color: Colors.grey.shade800,
          ),
        ),
        const SizedBox(height: 8),
        Text(
          'To track your steps accurately, we need access to your device sensors',
          textAlign: TextAlign.center,
          style: Theme.of(
            context,
          ).textTheme.bodyLarge?.copyWith(color: Colors.grey.shade600),
        ),
      ],
    );
  }

  Widget _buildPermissionCards() {
    return Column(
      children: [
        if (Platform.isIOS) ...[
          _buildPermissionCard(
            icon: Icons.favorite_rounded,
            title: 'Allow Apple Health',
            description:
                'We use Apple Health to ensure smooth step counting experience',
            color: Colors.pink.shade400,
          ),
        ] else ...[
          _buildPermissionCard(
            icon: Icons.directions_walk_rounded,
            title: 'Activity Recognition',
            description:
                'Allows the app to detect your physical activity and count steps',
            color: Colors.green,
          ),
          const SizedBox(height: 16),
          _buildPermissionCard(
            icon: Icons.notifications_active_rounded,
            title: 'Notifications',
            description:
                'Shows step count updates and service status notifications',
            color: Colors.orange,
          ),
        ],
      ],
    );
  }

  Widget _buildPermissionCard({
    required IconData icon,
    required String title,
    required String description,
    required Color color,
  }) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: Colors.grey.withValues(alpha: 0.1),
            blurRadius: 10,
            spreadRadius: 2,
          ),
        ],
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: color.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Icon(icon, color: color, size: 24),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 16,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  description,
                  style: TextStyle(color: Colors.grey.shade600, fontSize: 14),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildActionButton() {
    return SizedBox(
      width: double.infinity,
      child: CommonButton(
        label: _isLoading ? 'Requesting...' : 'Grant Permissions',
        icon: Icons.check_circle_outline_rounded,
        onPressed: _isLoading ? null : _requestPermissions,
        primaryColor: Colors.blue.shade600,
        shadowColor: Colors.blue,
        isEnabled: !_isLoading,
      ),
    );
  }
}
