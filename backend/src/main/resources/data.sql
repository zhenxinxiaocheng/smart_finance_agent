-- 插入测试用户 (密码: 123456, BCrypt加密)
INSERT IGNORE INTO `user` (`username`, `password`, `nickname`) VALUES
('test_user', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '测试用户');

-- 插入测试交易记录（需要确保上面的 test_user 的 id = 1）
INSERT IGNORE INTO `transaction` (`user_id`, `amount`, `type`, `category`, `description`, `transaction_date`) VALUES
(1, 35.50, 'EXPENSE', '餐饮', '午餐-公司食堂', '2026-05-20'),
(1, 12.00, 'EXPENSE', '交通', '地铁通勤', '2026-05-20'),
(1, 299.00, 'EXPENSE', '购物', '购买书籍', '2026-05-19'),
(1, 15000.00, 'INCOME', '工资', '5月工资', '2026-05-15'),
(1, 88.00, 'EXPENSE', '餐饮', '朋友聚餐', '2026-05-18'),
(1, 500.00, 'EXPENSE', '住房', '水电燃气费', '2026-05-10'),
(1, 45.00, 'EXPENSE', '交通', '打车费用', '2026-05-08'),
(1, 200.00, 'EXPENSE', '娱乐', '电影票两张', '2026-05-05');
