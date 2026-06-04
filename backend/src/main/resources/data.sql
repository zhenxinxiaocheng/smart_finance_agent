DELETE FROM expense_category WHERE user_id = 0;

INSERT INTO expense_category (user_id, name, icon, benchmark_min, benchmark_max, benchmark_label, sort_order) VALUES
(0, '餐饮', 'food', 25, 35, '食品支出', 1),
(0, '住房', 'home', 20, 35, '房租/房贷/水电', 2),
(0, '交通', 'transport', 8, 15, '通勤/出行', 3),
(0, '购物', 'shopping', 10, 20, '日用品/服装/数码', 4),
(0, '娱乐', 'entertainment', 5, 15, '休闲/旅游/游戏', 5),
(0, '医疗', 'medical', 3, 10, '看病/药品/体检', 6),
(0, '工资', 'salary', NULL, NULL, '工资收入', 7),
(0, '其他', 'other', NULL, NULL, '其他支出', 8);

INSERT IGNORE INTO user (username, password, nickname) VALUES
('test_user', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '测试用户');

INSERT IGNORE INTO `transaction` (user_id, amount, type, category, description, transaction_date) VALUES
(1, 35.50, 'EXPENSE', '餐饮', '午餐-公司食堂', '2026-05-20'),
(1, 12.00, 'EXPENSE', '交通', '地铁通勤', '2026-05-20'),
(1, 299.00, 'EXPENSE', '购物', '购买书籍', '2026-05-19'),
(1, 15000.00, 'INCOME', '工资', '5月工资', '2026-05-15'),
(1, 88.00, 'EXPENSE', '餐饮', '朋友聚餐', '2026-05-18'),
(1, 500.00, 'EXPENSE', '住房', '水电燃气费', '2026-05-10'),
(1, 45.00, 'EXPENSE', '交通', '打车费用', '2026-05-08'),
(1, 200.00, 'EXPENSE', '娱乐', '电影票两张', '2026-05-05');
