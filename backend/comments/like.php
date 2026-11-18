<?php
// POST /comments/like.php  (logged in)  body {comment_id, like: true|false}
// SETS my like (not a toggle), so a repeat changes nothing. The comment's author is notified
// the first time. Answer: {likes: [user ids]}.
declare(strict_types=1);
require_once __DIR__ . '/../lib/posts.php';
method('POST');

$me = current_user_id();
$commentId = int_param('comment_id');
$like = !in_array(param('like', '1'), ['0', '', 'false'], true);
$comment = row('SELECT * FROM comments WHERE id = ?', [$commentId]);
if ($comment === null) fail('Comment not found', 404);

once($me, function () use ($me, $commentId, $like, $comment) {
    if ($like) {
        $added = exec_sql('INSERT IGNORE INTO comment_likes (comment_id, user_id) VALUES (?, ?)', [$commentId, $me]);
        if ($added > 0) add_notification((int) $comment['user_id'], $me, 'comment_like', (int) $comment['post_id'], $comment['text']);
    } else {
        exec_sql('DELETE FROM comment_likes WHERE comment_id = ? AND user_id = ?', [$commentId, $me]);
    }
    $likes = array_map(fn($r) => (string) $r['user_id'], rows('SELECT user_id FROM comment_likes WHERE comment_id = ?', [$commentId]));
    return ['likes' => $likes];
});
