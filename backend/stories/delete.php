<?php
// POST /stories/delete.php  (logged in)  body {id}: deletes MY story and its file.
declare(strict_types=1);
require_once __DIR__ . '/../lib/stories.php';
method('POST');

$me = current_user_id();
$story = row('SELECT * FROM stories WHERE id = ?', [int_param('id')]);
if ($story !== null) {
    if ((int) $story['user_id'] !== $me) fail('You can only delete your own stories', 403);
    delete_file($story['media_path']);
    exec_sql('DELETE FROM stories WHERE id = ?', [(int) $story['id']]);   // views go with it
}
respond();   // deleting twice is fine
