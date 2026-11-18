<?php
// POST /notifications/delete.php  (logged in)  body {id}: removes one of MY notifications ("...").
declare(strict_types=1);
require_once __DIR__ . '/../lib/api.php';
method('POST');

$me = current_user_id();
exec_sql('DELETE FROM notifications WHERE id = ? AND user_id = ?', [int_param('id'), $me]);
respond();
