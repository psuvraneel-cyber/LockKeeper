import 'dart:async';
import 'package:flutter/material.dart';
import '../../core/models/installed_app_model.dart';
import '../../core/models/locked_app_model.dart';
import '../../core/models/protection_status_model.dart';
import '../../core/services/platform_bridge.dart';
import '../theme/app_theme.dart';
import 'app_detail_screen.dart';
import 'settings_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  List<InstalledAppModel> _installedApps = [];
  Map<String, LockedAppModel> _lockedApps = {};
  ProtectionStatusModel? _protectionStatus;
  bool _isLoading = true;
  String _searchQuery = '';
  String _filter = 'All'; // 'All', 'Locked', 'Unlocked'

  final TextEditingController _searchController = TextEditingController();
  Timer? _countdownTimer;
  StreamSubscription? _eventsSubscription;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadData();
    // Live update when native protection state changes
    _eventsSubscription = PlatformBridge.eventsStream.listen((_) {
      if (mounted) _loadData();
    }, onError: (_) {});

    // Refresh countdown badges every 2 seconds
    _countdownTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      if (mounted && _lockedApps.values.any((app) => app.isInCooldown)) {
        setState(() {});
      }
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _countdownTimer?.cancel();
    _eventsSubscription?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _loadData();
    }
  }

  Future<void> _loadData() async {
    try {
      final results = await Future.wait([
        PlatformBridge.getInstalledApps(),
        PlatformBridge.getLockedApps(),
        PlatformBridge.getProtectionStatus(),
      ]);

      if (!mounted) return;

      final apps = results[0] as List<InstalledAppModel>;
      final lockedList = results[1] as List<LockedAppModel>;
      final status = results[2] as ProtectionStatusModel;

      final lockedMap = <String, LockedAppModel>{};
      for (final locked in lockedList) {
        lockedMap[locked.packageName] = locked;
      }

      setState(() {
        _installedApps = apps;
        _lockedApps = lockedMap;
        _protectionStatus = status;
        _isLoading = false;
      });
    } catch (_) {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  Future<void> _toggleLock(InstalledAppModel app, bool isLocked) async {
    if (isLocked) {
      final existing = _lockedApps[app.packageName];
      final cooldown = existing?.cooldownMinutes ?? 15;
      final strict = existing?.strictLock ?? false;
      await PlatformBridge.setLockedApp(
        packageName: app.packageName,
        isLocked: true,
        cooldownMinutes: cooldown,
        strictLock: strict,
      );
    } else {
      await PlatformBridge.removeLockedApp(app.packageName);
    }
    await _loadData();
  }

  String _formatCooldown(Duration duration) {
    if (duration.isNegative || duration.inSeconds <= 0) return '0s';
    if (duration.inHours > 0) {
      return '${duration.inHours}h ${duration.inMinutes.remainder(60)}m';
    }
    if (duration.inMinutes > 0) {
      return '${duration.inMinutes}m ${duration.inSeconds.remainder(60)}s';
    }
    return '${duration.inSeconds}s';
  }

  List<InstalledAppModel> get _filteredApps {
    return _installedApps.where((app) {
      final isLocked = _lockedApps[app.packageName]?.isLocked ?? false;
      if (_filter == 'Locked' && !isLocked) return false;
      if (_filter == 'Unlocked' && isLocked) return false;

      if (_searchQuery.isNotEmpty) {
        final query = _searchQuery.toLowerCase();
        final nameMatch = app.name.toLowerCase().contains(query);
        final pkgMatch = app.packageName.toLowerCase().contains(query);
        return nameMatch || pkgMatch;
      }
      return true;
    }).toList();
  }

  Color _getShieldColor() {
    final status = _protectionStatus?.overallStatus ?? SecurityOverallStatus.unknown;
    return switch (status) {
      SecurityOverallStatus.protected => AppTheme.primary,
      SecurityOverallStatus.configured => AppTheme.textSecondary,
      SecurityOverallStatus.degraded => AppTheme.warning,
      SecurityOverallStatus.recoveryRequired => AppTheme.danger,
      _ => AppTheme.textMuted,
    };
  }

  IconData _getShieldIcon() {
    final status = _protectionStatus?.overallStatus ?? SecurityOverallStatus.unknown;
    return switch (status) {
      SecurityOverallStatus.protected => Icons.shield,
      SecurityOverallStatus.configured => Icons.shield_outlined,
      SecurityOverallStatus.degraded => Icons.gpp_maybe_rounded,
      SecurityOverallStatus.recoveryRequired => Icons.gpp_bad_rounded,
      _ => Icons.shield_outlined,
    };
  }

  Widget _buildDegradationBanner() {
    final status = _protectionStatus?.overallStatus;
    if (_protectionStatus == null || status == SecurityOverallStatus.protected) {
      return const SizedBox.shrink();
    }

    if (status == SecurityOverallStatus.recoveryRequired) {
      return Container(
        margin: const EdgeInsets.fromLTRB(16, 12, 16, 4),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: AppTheme.danger.withValues(alpha: 0.18),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppTheme.danger),
        ),
        child: Row(
          children: [
            const Icon(Icons.gpp_bad_rounded, color: AppTheme.danger, size: 30),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: const [
                  Text(
                    'Security Recovery Required',
                    style: TextStyle(
                      color: AppTheme.danger,
                      fontWeight: FontWeight.bold,
                      fontSize: 14,
                    ),
                  ),
                  SizedBox(height: 2),
                  Text(
                    'Device Admin is active, but local security state is missing. Recovery required.',
                    style: TextStyle(color: AppTheme.textSecondary, fontSize: 12),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            TextButton(
              onPressed: () {
                Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const SettingsScreen()),
                ).then((_) => _loadData());
              },
              style: TextButton.styleFrom(
                foregroundColor: AppTheme.danger,
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              ),
              child: const Text('Recover', style: TextStyle(fontWeight: FontWeight.bold)),
            ),
          ],
        ),
      );
    }

    if (status == SecurityOverallStatus.unknown) {
      return Container(
        margin: const EdgeInsets.fromLTRB(16, 12, 16, 4),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: AppTheme.warning.withValues(alpha: 0.15),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppTheme.warning.withValues(alpha: 0.5)),
        ),
        child: Row(
          children: [
            const Icon(Icons.help_outline_rounded, color: AppTheme.warning, size: 28),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: const [
                  Text(
                    'Security Status Unknown',
                    style: TextStyle(
                      color: AppTheme.warning,
                      fontWeight: FontWeight.bold,
                      fontSize: 14,
                    ),
                  ),
                  SizedBox(height: 2),
                  Text(
                    'Native protection state could not be verified.',
                    style: TextStyle(color: AppTheme.textSecondary, fontSize: 12),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            TextButton(
              onPressed: _loadData,
              style: TextButton.styleFrom(foregroundColor: AppTheme.primary),
              child: const Text('Retry', style: TextStyle(fontWeight: FontWeight.bold)),
            ),
          ],
        ),
      );
    }

    final reasons = _protectionStatus!.degradedReasons;
    final reasonText = reasons.isNotEmpty ? reasons.first : 'Required permissions or services are not operational';

    return Container(
      margin: const EdgeInsets.fromLTRB(16, 12, 16, 4),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppTheme.warning.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppTheme.warning.withValues(alpha: 0.5)),
      ),
      child: Row(
        children: [
          const Icon(Icons.warning_amber_rounded, color: AppTheme.warning, size: 28),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Protection Degraded',
                  style: TextStyle(
                    color: AppTheme.warning,
                    fontWeight: FontWeight.bold,
                    fontSize: 14,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  reasonText,
                  style: const TextStyle(
                    color: AppTheme.textSecondary,
                    fontSize: 12,
                  ),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          TextButton(
            onPressed: () {
              Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const SettingsScreen()),
              ).then((_) => _loadData());
            },
            style: TextButton.styleFrom(
              foregroundColor: AppTheme.primary,
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            ),
            child: const Text('Fix', style: TextStyle(fontWeight: FontWeight.bold)),
          ),
        ],
      ),
    );
  }

  Widget _buildFilterChips() {
    final lockedCount = _lockedApps.values.where((e) => e.isLocked).length;
    final totalCount = _installedApps.length;
    final unlockedCount = totalCount - lockedCount;

    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 8.0),
      child: Row(
        children: [
          _buildFilterChip('All', 'All ($totalCount)'),
          const SizedBox(width: 8),
          _buildFilterChip('Locked', 'Locked ($lockedCount)'),
          const SizedBox(width: 8),
          _buildFilterChip('Unlocked', 'Unlocked ($unlockedCount)'),
        ],
      ),
    );
  }

  Widget _buildFilterChip(String filterKey, String label) {
    final isSelected = _filter == filterKey;
    return ChoiceChip(
      label: Text(label),
      selected: isSelected,
      onSelected: (selected) {
        if (selected) {
          setState(() => _filter = filterKey);
        }
      },
      selectedColor: AppTheme.primary,
      backgroundColor: AppTheme.surfaceElevated,
      labelStyle: TextStyle(
        color: isSelected ? const Color(0xFF0F172A) : AppTheme.textSecondary,
        fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
        fontSize: 13,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final filteredList = _filteredApps;

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(6),
              decoration: BoxDecoration(
                color: _getShieldColor().withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Icon(_getShieldIcon(), color: _getShieldColor(), size: 20),
            ),
            const SizedBox(width: 10),
            const Text('LockKeeper'),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            tooltip: 'Settings & Security',
            onPressed: () {
              Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const SettingsScreen()),
              ).then((_) => _loadData());
            },
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            _buildDegradationBanner(),

            // Search Bar
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
              child: TextField(
                controller: _searchController,
                onChanged: (val) => setState(() => _searchQuery = val.trim()),
                decoration: InputDecoration(
                  hintText: 'Search installed applications...',
                  prefixIcon: const Icon(Icons.search, color: AppTheme.textMuted),
                  suffixIcon: _searchQuery.isNotEmpty
                      ? IconButton(
                          icon: const Icon(Icons.clear, color: AppTheme.textMuted),
                          onPressed: () {
                            _searchController.clear();
                            setState(() => _searchQuery = '');
                          },
                        )
                      : null,
                ),
              ),
            ),

            _buildFilterChips(),

            // Apps List
            Expanded(
              child: _isLoading
                  ? const Center(child: CircularProgressIndicator())
                  : filteredList.isEmpty
                      ? Center(
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(
                                _searchQuery.isNotEmpty
                                    ? Icons.search_off_rounded
                                    : Icons.apps_outlined,
                                size: 56,
                                color: AppTheme.textMuted,
                              ),
                              const SizedBox(height: 12),
                              Text(
                                _searchQuery.isNotEmpty
                                    ? 'No apps matching "$_searchQuery"'
                                    : 'No applications found',
                                style: const TextStyle(
                                  color: AppTheme.textSecondary,
                                  fontSize: 16,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                            ],
                          ),
                        )
                      : RefreshIndicator(
                          onRefresh: _loadData,
                          color: AppTheme.primary,
                          backgroundColor: AppTheme.surface,
                          child: ListView.separated(
                            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                            itemCount: filteredList.length,
                            separatorBuilder: (_, index) => const SizedBox(height: 8),
                            itemBuilder: (context, index) {
                              final app = filteredList[index];
                              final lockedModel = _lockedApps[app.packageName];
                              final isLocked = lockedModel?.isLocked ?? false;
                              final isInCooldown = lockedModel?.isInCooldown ?? false;
                              final iconBytes = app.iconBytes;

                              return Card(
                                child: InkWell(
                                  onTap: () async {
                                    final updated = await Navigator.of(context).push<bool>(
                                      MaterialPageRoute(
                                        builder: (_) => AppDetailScreen(
                                          app: app,
                                          lockedConfig: lockedModel,
                                        ),
                                      ),
                                    );
                                    if (updated == true) {
                                      _loadData();
                                    }
                                  },
                                  borderRadius: BorderRadius.circular(16),
                                  child: Padding(
                                    padding: const EdgeInsets.symmetric(
                                      horizontal: 14.0,
                                      vertical: 12.0,
                                    ),
                                    child: Row(
                                      children: [
                                        // App Icon
                                        Container(
                                          width: 44,
                                          height: 44,
                                          decoration: BoxDecoration(
                                            color: AppTheme.surfaceElevated,
                                            borderRadius: BorderRadius.circular(10),
                                            border: Border.all(color: AppTheme.border),
                                          ),
                                          clipBehavior: Clip.antiAlias,
                                          child: iconBytes != null
                                              ? Image.memory(iconBytes, fit: BoxFit.cover)
                                              : const Icon(Icons.android, color: AppTheme.textMuted),
                                        ),
                                        const SizedBox(width: 14),

                                        // App Info & Status Badges
                                        Expanded(
                                          child: Column(
                                            crossAxisAlignment: CrossAxisAlignment.start,
                                            children: [
                                              Text(
                                                app.name,
                                                style: const TextStyle(
                                                  fontWeight: FontWeight.bold,
                                                  fontSize: 15,
                                                  color: AppTheme.textPrimary,
                                                ),
                                                maxLines: 1,
                                                overflow: TextOverflow.ellipsis,
                                              ),
                                              const SizedBox(height: 4),
                                              if (isLocked)
                                                Wrap(
                                                  spacing: 6,
                                                  runSpacing: 4,
                                                  crossAxisAlignment: WrapCrossAlignment.center,
                                                  children: [
                                                    // Cooldown setting badge
                                                    Container(
                                                      padding: const EdgeInsets.symmetric(
                                                        horizontal: 6,
                                                        vertical: 2,
                                                      ),
                                                      decoration: BoxDecoration(
                                                        color: AppTheme.primary.withValues(alpha: 0.15),
                                                        borderRadius: BorderRadius.circular(4),
                                                      ),
                                                      child: Text(
                                                        '${lockedModel?.cooldownMinutes ?? 15}m cooldown',
                                                        style: const TextStyle(
                                                          color: AppTheme.primary,
                                                          fontSize: 11,
                                                          fontWeight: FontWeight.w600,
                                                        ),
                                                      ),
                                                    ),

                                                    // Strict mode indicator
                                                    if (lockedModel?.strictLock ?? false)
                                                      Container(
                                                        padding: const EdgeInsets.symmetric(
                                                          horizontal: 6,
                                                          vertical: 2,
                                                        ),
                                                        decoration: BoxDecoration(
                                                          color: AppTheme.warning.withValues(alpha: 0.15),
                                                          borderRadius: BorderRadius.circular(4),
                                                        ),
                                                        child: const Text(
                                                          'Strict',
                                                          style: TextStyle(
                                                            color: AppTheme.warning,
                                                            fontSize: 11,
                                                            fontWeight: FontWeight.w600,
                                                          ),
                                                        ),
                                                      ),

                                                    // Active Cooldown indicator
                                                    if (isInCooldown)
                                                      Container(
                                                        padding: const EdgeInsets.symmetric(
                                                          horizontal: 6,
                                                          vertical: 2,
                                                        ),
                                                        decoration: BoxDecoration(
                                                          color: AppTheme.danger.withValues(alpha: 0.18),
                                                          borderRadius: BorderRadius.circular(4),
                                                        ),
                                                        child: Row(
                                                          mainAxisSize: MainAxisSize.min,
                                                          children: [
                                                            const Icon(
                                                              Icons.timer_outlined,
                                                              color: AppTheme.danger,
                                                              size: 11,
                                                            ),
                                                            const SizedBox(width: 3),
                                                            Text(
                                                              _formatCooldown(
                                                                lockedModel!.remainingCooldown,
                                                              ),
                                                              style: const TextStyle(
                                                                color: AppTheme.danger,
                                                                fontSize: 11,
                                                                fontWeight: FontWeight.bold,
                                                              ),
                                                            ),
                                                          ],
                                                        ),
                                                      ),
                                                  ],
                                                )
                                              else
                                                Text(
                                                  app.packageName,
                                                  style: const TextStyle(
                                                    fontSize: 12,
                                                    color: AppTheme.textMuted,
                                                  ),
                                                  maxLines: 1,
                                                  overflow: TextOverflow.ellipsis,
                                                ),
                                            ],
                                          ),
                                        ),

                                        const SizedBox(width: 8),

                                        // Lock Switch
                                        Switch(
                                          value: isLocked,
                                          onChanged: (val) => _toggleLock(app, val),
                                        ),
                                      ],
                                    ),
                                  ),
                                ),
                              );
                            },
                          ),
                        ),
            ),
          ],
        ),
      ),
    );
  }
}
