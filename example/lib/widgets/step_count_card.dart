import 'package:flutter/material.dart';

class StepCountCard extends StatelessWidget {
  final Color primaryColor;
  final Color shadowColor;
  final IconData icon;
  final String title;
  final String subtitle;
  final int stepCount;
  final bool hasPermission;

  const StepCountCard({
    super.key,
    required this.primaryColor,
    required this.shadowColor,
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.stepCount,
    required this.hasPermission,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        gradient: hasPermission
            ? LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [primaryColor, primaryColor],
                stops: const [0.0, 1.0],
              )
            : LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [Colors.grey.shade300, Colors.grey.shade500],
                stops: const [0.0, 1.0],
              ),
        borderRadius: BorderRadius.circular(24),
        boxShadow: [
          BoxShadow(
            color: hasPermission
                ? shadowColor.withValues(alpha: 0.3)
                : Colors.grey.withValues(alpha: 0.3),
            blurRadius: 12,
            offset: const Offset(0, 6),
            spreadRadius: 0,
          ),
        ],
      ),
      child: Container(
        padding: const EdgeInsets.all(14),
        child: Column(
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.2),
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Icon(icon, size: 32, color: Colors.white),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(
                          color: Colors.white,
                          fontWeight: FontWeight.w600,
                          letterSpacing: 0.5,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        subtitle,
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: Colors.white.withValues(alpha: 0.8),
                          fontSize: 14,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Container(
              padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 20),
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(20),
                border: Border.all(
                  color: Colors.white.withValues(alpha: 0.2),
                  width: 1,
                ),
              ),
              child: Column(
                children: [
                  Text(
                    '$stepCount',
                    style: Theme.of(context).textTheme.displayLarge?.copyWith(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                      fontSize: 48,
                      height: 1.1,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
