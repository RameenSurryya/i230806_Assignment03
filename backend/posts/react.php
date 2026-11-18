<?php
// POST /posts/react.php  (logged in)  body {post_id, type}  type = like|love|haha|wow|sad|angry,
// or "" to remove my reaction. It SETS the reaction (it doesn't toggle), so a repeat from the
// offline queue changes nothing. The author is notified the first time I react.
// Answer: {reactions} = all reactions of the post after the change.
declare(strict_types=1);
require_once __DIR__ . '/../lib/posts.php';
method('POST');

$me = current_user_id();
$postId = int_param('post_id');
$type = param('type', '') ?? '';
$post = row('SELECT * FROM posts WHERE id = ?', [$postId]);
if ($post === null) fail('Post not found', 404);
if ($type !== '' && !in_array($type, ['like', 'love', 'haha', 'wow', 'sad', 'angry'], true)) fail('Unknown reaction');

once($me, function () use ($me, $postId, $type, $post) {
    if ($type === '') {
        exec_sql('DELETE FROM reactions WHERE post_id = ? AND user_id = ?', [$postId, $me]);
    } else {
        $had = row('SELECT 1 FROM reactions WHERE post_id = ? AND user_id = ?', [$postId, $me]);
        exec_sql(
            'INSERT INTO reactions (post_id, user_id, type, created_at) VALUES (?, ?, ?, ?)
             ON DUPLICATE KEY UPDATE type = VALUES(type)',
            [$postId, $me, $type, now_ms()]
        );
        if ($had === null) add_notification((int) $post['user_id'], $me, 'post_reaction', $postId, $type);
    }
    $reactions = [];
    foreach (rows('SELECT user_id, type FROM reactions WHERE post_id = ?', [$postId]) as $r) {
        $reactions[(string) $r['user_id']] = $r['type'];
    }
    return ['reactions' => (object) $reactions];
});
