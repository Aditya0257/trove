/// AccountProfile - the signed-in user's own profile + security summary.
/// Mirrors backend AccountController.AccountResponse
/// {email, displayName, admin, twoFactorEnabled, avatarUrl, pendingEmail, createdAt}.
library;

class AccountProfile {
  const AccountProfile({
    required this.email,
    required this.displayName,
    required this.admin,
    required this.twoFactorEnabled,
    this.avatarUrl,
    this.pendingEmail,
    this.createdAt,
  });

  final String email;
  final String displayName;
  final bool admin;
  final bool twoFactorEnabled;
  final String? avatarUrl;
  final String? pendingEmail;
  final String? createdAt;

  factory AccountProfile.fromJson(Map<String, dynamic> json) => AccountProfile(
        email: json['email'] as String? ?? '',
        displayName: json['displayName'] as String? ?? '',
        admin: (json['admin'] as bool?) ?? false,
        twoFactorEnabled: (json['twoFactorEnabled'] as bool?) ?? false,
        avatarUrl: json['avatarUrl'] as String?,
        pendingEmail: json['pendingEmail'] as String?,
        createdAt: json['createdAt'] as String?,
      );
}

/// One account in the admin's user list (for the delete-account picker + approvals).
class AdminUser {
  const AdminUser({
    required this.id,
    required this.email,
    required this.displayName,
    required this.status,
    required this.admin,
    this.createdAt,
  });

  final String id;
  final String email;
  final String displayName;
  final String status;
  final bool admin;
  final String? createdAt;

  factory AdminUser.fromJson(Map<String, dynamic> json) => AdminUser(
        id: json['id'] as String,
        email: json['email'] as String? ?? '',
        displayName: json['displayName'] as String? ?? '',
        status: json['status'] as String? ?? '',
        admin: (json['admin'] as bool?) ?? false,
        createdAt: json['createdAt'] as String?,
      );
}
