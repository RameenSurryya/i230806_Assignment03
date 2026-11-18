<?php
// POST /posts/delete.php  (logged in)  body {id}: deletes MY post, its comments, reactions and picture files.
declare(strict_types=1);
require_once __DIR__ . '/../lib/posts.php';
method('POST');

$me = current_user_id();
$id = int_param('id');
$post = row('SELECT * FROM posts WHERE id = ?', [$id]);
if ($post !== null) {
    if ((int) $post['user_id'] !== $me) fail('You can only delete your own posts', 403);
    foreach (rows('SELECT path FROM post_images WHERE post_id = ?', [$id]) as $r) delete_file($r['path']);
    foreach (rows('SELECT image_path FROM comments WHERE post_id = ?', [$id]) as $r) delete_file($r['image_path']);
    exec_sql('DELETE FROM notifications WHERE post_id = ?', [$id]);
    exec_sql('DELETE FROM posts WHERE id = ?', [$id]);   // pictures rows, comments, reactions: ON DELETE CASCADE
}
respond();   // deleting twice is fine
