<?php
// POST /comments/delete.php  (logged in)  body {id}: deletes MY comment (its replies and likes go with it).
declare(strict_types=1);
require_once __DIR__ . '/../lib/posts.php';
method('POST');

$me = current_user_id();
$id = int_param('id');
$comment = row('SELECT * FROM comments WHERE id = ?', [$id]);
if ($comment !== null) {
    if ((int) $comment['user_id'] !== $me) fail('You can only delete your own comments', 403);
    delete_file($comment['image_path']);
    // Replies are children of this comment: delete them (and their files) first.
    foreach (rows('SELECT image_path FROM comments WHERE parent_id = ?', [$id]) as $r) delete_file($r['image_path']);
    exec_sql('DELETE FROM comments WHERE parent_id = ?', [$id]);
    exec_sql('DELETE FROM comments WHERE id = ?', [$id]);
}
$count = row('SELECT COUNT(*) AS n FROM comments WHERE post_id = ?', [(int) ($comment['post_id'] ?? 0)]);
respond(['comment_count' => (int) $count['n']]);
