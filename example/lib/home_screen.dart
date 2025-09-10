import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:steps_count/steps_count.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  int _stepCount = 0;
  bool _isInitialized = false;
  bool _hasPermission = false;
  bool _isServiceRunning = false;
  final _stepsCounterPlugin = StepsCount();

  @override
  void initState() {
    super.initState();
    _checkAllStatus();
  }

  Future<void> _checkAllStatus() async {
    await _checkPermissionStatus();
    await _checkServiceStatus();
    _isInitialized = true;
    setState(() {});
  }

  Future<void> _checkServiceStatus() async {
    _isServiceRunning = await _stepsCounterPlugin.isServiceRunning();
    setState(() {});
  }

  Future<void> _checkPermissionStatus() async {
    final activityRecognitionStatus =
        await Permission.activityRecognition.status;
    final notificationStatus = await Permission.notification.status;
    if (activityRecognitionStatus.isGranted && notificationStatus.isGranted) {
      _hasPermission = true;
    } else {
      _hasPermission = false;
    }
    setState(() {});
  }

  Future<void> _requestPermission() async {
    if (_hasPermission) {
      debugPrint('Permission already granted');
      return;
    }
    try {
      final activityRecognitionStatus = await Permission.activityRecognition
          .request();
      final notificationStatus = await Permission.notification.request();
      if (activityRecognitionStatus.isPermanentlyDenied ||
          notificationStatus.isPermanentlyDenied) {
        showSnackBar('Permission permanently denied', color: Colors.red);
        await openAppSettings();
        _requestPermission();
        return;
      }
      await _checkPermissionStatus();
      if (activityRecognitionStatus.isGranted && notificationStatus.isGranted) {
        _hasPermission = true;
        showSnackBar('Permission granted!');
      } else {
        _hasPermission = false;
        showSnackBar('Permission denied', color: Colors.red);
      }
    } catch (e) {
      showSnackBar('Error requesting permission: $e', color: Colors.red);
      debugPrint('Error requesting permission: $e');
    }
    setState(() {});
  }

  Future<void> _updateStepCount() async {
    try {
      final stepCount = await _stepsCounterPlugin.getStepCount();
      if (mounted) {
        setState(() {
          _stepCount = stepCount;
        });
      }
    } catch (e) {
      // Silently handle step count errors to avoid spam
    }
  }

  Future<void> _startService() async {
    if (!_hasPermission) {
      showSnackBar('Please grant permission first', color: Colors.red);
      return;
    }
    try {
      await _stepsCounterPlugin.startBackgroundService();
      showSnackBar('Service started');
      _updateStepCount();
      _checkServiceStatus();
    } on PlatformException catch (e) {
      showSnackBar('Error starting service: ${e.message}', color: Colors.red);
      debugPrint('Error starting service: ${e.message}');
    }
  }

  Future<void> _stopService() async {
    if (!_hasPermission) {
      showSnackBar('Please grant permission first', color: Colors.red);
      return;
    }
    try {
      await _stepsCounterPlugin.stopBackgroundService();
      showSnackBar('Service stopped');
      _checkServiceStatus();
    } on PlatformException catch (e) {
      showSnackBar('Error stopping service: ${e.message}', color: Colors.red);
      debugPrint('Error stopping service: ${e.message}');
    }
  }

  void showSnackBar(String message, {Color color = Colors.green}) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message), backgroundColor: color));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Steps Count')),
      body: _isInitialized ? _buildBody() : _buildLoading(),
    );
  }

  Widget _buildLoading() {
    return const Center(child: CircularProgressIndicator());
  }

  Widget _buildBody() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(20.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            _buildCountContainer(),
            const SizedBox(height: 30),
            if (!_hasPermission) ...[
              _buildPermissionBtn(),
              const SizedBox(height: 30),
            ],
            _buildServiceRequestBtn(),
          ],
        ),
      ),
    );
  }

  Widget _buildCountContainer() {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: _hasPermission ? Colors.green.shade100 : Colors.grey.shade100,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(
          color: _hasPermission ? Colors.green : Colors.grey,
          width: 2,
        ),
      ),
      child: Column(
        children: [
          Text('Step Count', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 10),
          Text(
            '$_stepCount',
            style: Theme.of(context).textTheme.headlineLarge?.copyWith(
              color: _hasPermission
                  ? Colors.green.shade700
                  : Colors.grey.shade600,
              fontWeight: FontWeight.bold,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPermissionBtn() {
    return ElevatedButton(
      onPressed: _requestPermission,
      style: ElevatedButton.styleFrom(
        backgroundColor: Colors.blue,
        foregroundColor: Colors.white,
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
      ),
      child: const Text('Grant Permission'),
    );
  }

  Widget _buildServiceRequestBtn() {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
      children: [
        ElevatedButton.icon(
          onPressed: _isServiceRunning ? null : _startService,
          icon: const Icon(Icons.play_arrow),
          label: const Text('Start Service'),
          style: ElevatedButton.styleFrom(
            backgroundColor: Colors.green,
            foregroundColor: Colors.white,
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          ),
        ),
        ElevatedButton.icon(
          onPressed: _isServiceRunning ? _stopService : null,
          icon: const Icon(Icons.stop),
          label: const Text('Stop Service'),
          style: ElevatedButton.styleFrom(
            backgroundColor: Colors.red,
            foregroundColor: Colors.white,
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          ),
        ),
      ],
    );
  }
}
