import 'package:flutter/material.dart';
import 'dart:async';

import 'package:flutter/services.dart';
import 'package:steps_count/steps_count.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  String _serviceStatus = 'Service not started';
  bool _isServiceRunning = false;
  int _stepCount = 0;
  bool _hasPermission = false;
  final _stepsCounterPlugin = StepsCount();

  @override
  void initState() {
    super.initState();
    _checkPermissionStatus();
    _checkServiceStatus();
    _startPeriodicStepCountUpdate();
  }

  void _startPeriodicStepCountUpdate() {
    Timer.periodic(const Duration(seconds: 2), (timer) {
      if (_isServiceRunning) {
        _updateStepCount();
      }
    });
  }

  Future<void> _checkPermissionStatus() async {
    try {
      final hasPermission = await _stepsCounterPlugin.checkPermission();
      if (mounted) {
        setState(() {
          _hasPermission = hasPermission;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _hasPermission = false;
        });
      }
    }
  }

  Future<void> _requestPermission() async {
    try {
      final result = await _stepsCounterPlugin.requestPermission();
      if (mounted) {
        setState(() {
          _hasPermission = result;
        });
        if (result) {
          _showSnackBar('Permission granted!', Colors.green);
        } else {
          _showSnackBar('Permission denied', Colors.red);
        }
      }
    } catch (e) {
      _showSnackBar('Error requesting permission: $e', Colors.red);
    }
  }

  void _showSnackBar(String message, Color color) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: color,
        duration: const Duration(seconds: 2),
      ),
    );
  }

  Future<void> _checkServiceStatus() async {
    try {
      final isRunning = await _stepsCounterPlugin.isServiceRunning();
      if (mounted) {
        setState(() {
          _isServiceRunning = isRunning;
          _serviceStatus = isRunning
              ? 'Service is running'
              : 'Service not started';
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _serviceStatus = 'Error checking service status: $e';
        });
      }
    }
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
      _showSnackBar('Please grant permission first', Colors.orange);
      return;
    }

    try {
      final result = await _stepsCounterPlugin.startBackgroundService();
      if (mounted) {
        setState(() {
          _isServiceRunning = result;
          _serviceStatus = result
              ? 'Service started successfully'
              : 'Failed to start service';
        });
        if (result) {
          _updateStepCount();
        }
      }
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() {
          _serviceStatus = 'Error starting service: ${e.message}';
        });
      }
    }
  }

  Future<void> _stopService() async {
    try {
      final result = await _stepsCounterPlugin.forceStopBackgroundService();
      if (mounted) {
        setState(() {
          _isServiceRunning = !result;
          _serviceStatus = result
              ? 'Service stopped successfully'
              : 'Failed to stop service';
          if (result) {
            _stepCount = 0;
          }
        });
      }
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() {
          _serviceStatus = 'Error stopping service: ${e.message}';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('Steps Counter Plugin')),
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(20.0),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  'Steps Counter',
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
                const SizedBox(height: 40),
                Container(
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    color: _isServiceRunning
                        ? Colors.green.shade100
                        : Colors.grey.shade100,
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(
                      color: _isServiceRunning ? Colors.green : Colors.grey,
                      width: 2,
                    ),
                  ),
                  child: Column(
                    children: [
                      Text(
                        'Step Count',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 10),
                      Text(
                        '$_stepCount',
                        style: Theme.of(context).textTheme.headlineLarge
                            ?.copyWith(
                              color: _isServiceRunning
                                  ? Colors.green.shade700
                                  : Colors.grey.shade600,
                              fontWeight: FontWeight.bold,
                            ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 30),
                // Permission Status
                Container(
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: _hasPermission
                        ? Colors.green.shade100
                        : Colors.red.shade100,
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(
                      color: _hasPermission ? Colors.green : Colors.red,
                      width: 2,
                    ),
                  ),
                  child: Column(
                    children: [
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(
                            _hasPermission ? Icons.check_circle : Icons.cancel,
                            color: _hasPermission ? Colors.green : Colors.red,
                            size: 20,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            'Permission Status',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      Text(
                        _hasPermission ? 'Granted' : 'Not Granted',
                        style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                          color: _hasPermission
                              ? Colors.green.shade700
                              : Colors.red.shade700,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 20),
                // Permission Request Button
                if (!_hasPermission)
                  ElevatedButton.icon(
                    onPressed: _requestPermission,
                    icon: const Icon(Icons.security),
                    label: const Text('Request Permission'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.blue,
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(
                        horizontal: 20,
                        vertical: 12,
                      ),
                    ),
                  ),
                if (_hasPermission)
                  ElevatedButton.icon(
                    onPressed: _checkPermissionStatus,
                    icon: const Icon(Icons.refresh),
                    label: const Text('Refresh Permission'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.grey,
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(
                        horizontal: 20,
                        vertical: 12,
                      ),
                    ),
                  ),
                const SizedBox(height: 20),
                // Service Status
                Text(
                  'Service Status:',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 10),
                Text(
                  _serviceStatus,
                  style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                    color: _isServiceRunning
                        ? Colors.green.shade700
                        : Colors.grey.shade600,
                  ),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 40),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    ElevatedButton.icon(
                      onPressed: (_isServiceRunning || !_hasPermission)
                          ? null
                          : _startService,
                      icon: const Icon(Icons.play_arrow),
                      label: const Text('Start Service'),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.green,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(
                          horizontal: 20,
                          vertical: 12,
                        ),
                      ),
                    ),
                    ElevatedButton.icon(
                      onPressed: _isServiceRunning ? _stopService : null,
                      icon: const Icon(Icons.stop),
                      label: const Text('Stop Service'),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.red,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(
                          horizontal: 20,
                          vertical: 12,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 20),
                if (_isServiceRunning)
                  Text(
                    'Steps are being counted in the background',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Colors.green.shade600,
                      fontStyle: FontStyle.italic,
                    ),
                    textAlign: TextAlign.center,
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
