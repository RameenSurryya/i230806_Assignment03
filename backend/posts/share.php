<?php
// POST /posts/share.php  (logged in)  body {id}: counts one more share (Share to chat).
declare(strict_types=1);
require_once __DIR__ . '/../lib/posts.php';
method('POST');

$me = current_user_id();
$id = int_param('id');
once($me, function () use ($id) {
    exec_sql('UPDATE posts SET share_count = share_count + 1 WHERE id = ?', [$id]);
    $post = row('SELECT share_count FROM posts WHERE id = ?', [$id]);
    return ['share_count' => (int) ($post['share_count'] ?? 0)];
});
