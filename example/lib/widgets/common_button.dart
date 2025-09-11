import 'package:flutter/material.dart';

class CommonButton extends StatelessWidget {
  final bool isEnabled;
  final Color primaryColor;
  final Color shadowColor;
  final IconData icon;
  final String label;
  final VoidCallback? onPressed;

  const CommonButton({
    super.key,
    required this.isEnabled,
    required this.primaryColor,
    required this.shadowColor,
    required this.icon,
    required this.label,
    this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 60,
      margin: const EdgeInsets.symmetric(horizontal: 4),
      decoration: BoxDecoration(
        gradient: isEnabled
            ? LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [primaryColor.withValues(alpha: 0.8), primaryColor],
              )
            : LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [Colors.grey.shade500, Colors.grey.shade400],
              ),
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: isEnabled
                ? shadowColor.withValues(alpha: 0.3)
                : Colors.grey.withValues(alpha: 0.2),
            blurRadius: 8,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onPressed,
          borderRadius: BorderRadius.circular(16),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.2),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(icon, color: Colors.white, size: 20),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    label,
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      color: Colors.white,
                      fontWeight: FontWeight.w600,
                    ),
                    textAlign: TextAlign.center,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
