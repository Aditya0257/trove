/// Category — a filing category. Mirrors backend CategoryView {code, label, global}.
library;

class Category {
  const Category({required this.code, required this.label, this.global = true});

  final String code;
  final String label;
  final bool global;

  factory Category.fromJson(Map<String, dynamic> json) => Category(
        code: json['code'] as String,
        label: (json['label'] as String?) ?? (json['code'] as String),
        global: json['global'] == true,
      );
}
