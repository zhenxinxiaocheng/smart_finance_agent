INSERT IGNORE INTO user (username, password, nickname) VALUES
('test_user', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '测试用户');

INSERT IGNORE INTO expense_category (user_id, name, icon, sort_order) VALUES
(1, '餐饮', 'food', 1),
(1, '交通', 'transport', 2),
(1, '购物', 'shopping', 3),
(1, '住房', 'home', 4),
(1, '娱乐', 'entertainment', 5),
(1, '医疗', 'medical', 6),
(1, '教育', 'education', 7),
(1, '通讯', 'communication', 8),
(1, '人情', 'social', 9),
(1, '金融', 'finance', 10),
(1, '宠物', 'pet', 11),
(1, '日常', 'daily', 12),
(1, '工资', 'salary', 13),
(1, '兼职', 'parttime', 14),
(1, '投资收益', 'investment', 15),
(1, '退款', 'refund', 16),
(1, '其他', 'other', 99);
