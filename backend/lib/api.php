<?php
// Helpers every endpoint uses: JSON answers, reading parameters, the login check,
// the server clock, and the "run this once" protection for offline sync.
declare(strict_types=1);
require_once __DIR__ . '/db.php';
require_once __DIR__ . '/upload.php';

header('Content-Type: application/json; charset=utf-8');

// Any PHP error becomes a JSON answer instead of an HTML page the app can't read.
set_exception_handler(function (Throwable $e) {
    error_log('Kinnect API: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['ok' => false, 'error' => 'Server error']);
});

/** Server time in milliseconds (same unit as Android's System.currentTimeMillis()). */
function now_ms(): int
{
    return (int) floor(microtime(true) * 1000);
}

/** Sends {"ok": true, ...data} and stops. */
function respond(array $data = [], int $status = 200): never
{
    http_response_code($status);
    echo json_encode(['ok' => $status < 400] + $data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

/** Sends {"ok": false, "error": message} with an HTTP error code and stops. */
function fail(string $message, int $status = 400): never
{
    respond(['error' => $message], $status);
}

/** Only accept this HTTP method (GET or POST). */
function method(string $expected): void
{
    if ($_SERVER['REQUEST_METHOD'] !== $expected) fail('Use ' . $expected, 405);
}

// ---------------------------------------------------------------------------------------
// Parameters: JSON body, form fields (multipart uploads) or the query string
// ---------------------------------------------------------------------------------------

function body(): array
{
    static $data = null;
    if ($data === null) {
        $type = $_SERVER['CONTENT_TYPE'] ?? '';
        if (str_contains($type, 'application/json')) {
            $data = json_decode(file_get_contents('php://input') ?: '[]', true) ?: [];
        } else {
            $data = $_POST;
        }
    }
    return $data;
}

function param(string $key, ?string $default = null): ?string
{
    $value = body()[$key] ?? $_GET[$key] ?? $default;
    return $value === null ? null : trim((string) $value);
}

function required(string $key): string
{
    $value = param($key);
    if ($value === null || $value === '') fail("Missing $key");
    return $value;
}

function int_param(string $key, int $default = 0): int
{
    return (int) (param($key) ?? $default);
}

// ---------------------------------------------------------------------------------------
// Login check
// ---------------------------------------------------------------------------------------

/** The token from the "Authorization: Bearer <token>" header. */
function bearer_token(): ?string
{
    $header = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if ($header === '' && function_exists('getallheaders')) {
        foreach (getallheaders() as $name => $value) {
            if (strcasecmp($name, 'Authorization') === 0) $header = $value;
        }
    }
    return preg_match('/Bearer\s+(\S+)/', $header, $m) ? $m[1] : null;
}

/**
 * The logged-in user's id, or a 401 answer. Every request also counts as a heartbeat,
 * so the user shows as online while the app is talking to the server.
 */
function current_user_id(): int
{
    $token = bearer_token();
    if ($token === null) fail('Not logged in', 401);
    $uid = row('SELECT user_id FROM sessions WHERE token_hash = ? AND revoked = 0', [hash('sha256', $token)]);
    if ($uid === null) fail('Session expired', 401);
    exec_sql('UPDATE users SET is_online = 1, last_seen = ? WHERE id = ?', [now_ms(), $uid['user_id']]);
    return (int) $uid['user_id'];
}

// ---------------------------------------------------------------------------------------
// No duplicates: actions queued offline are sent with a UUID in the X-Request-Id header
// ---------------------------------------------------------------------------------------

function request_id(): ?string
{
    $id = $_SERVER['HTTP_X_REQUEST_ID'] ?? param('request_id');
    return ($id !== null && preg_match('/^[0-9a-fA-F-]{36}$/', $id)) ? strtolower($id) : null;
}

/**
 * Runs [action] once per request UUID and answers with its result.
 * A repeated UUID (a retry after a lost connection) gets the saved answer and nothing runs again.
 * The action and the saved answer are written in one transaction, so it is all or nothing.
 */
function once(int $userId, callable $action): never
{
    $id = request_id();
    if ($id !== null) {
        $saved = row('SELECT response FROM processed_requests WHERE request_id = ?', [$id]);
        if ($saved !== null) {
            echo $saved['response'];
            exit;
        }
    }
    db()->beginTransaction();
    try {
        $json = json_encode(['ok' => true] + $action(), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        if ($id !== null) {
            exec_sql(
                'INSERT INTO processed_requests (request_id, user_id, response, created_at) VALUES (?, ?, ?, ?)',
                [$id, $userId, $json, now_ms()]
            );
        }
        db()->commit();
    } catch (Throwable $e) {
        db()->rollBack();
        throw $e;
    }
    echo $json;
    exit;
}

/** "3_12": the same chat id for both people, smaller id first. */
function chat_id(int $a, int $b): string
{
    return min($a, $b) . '_' . max($a, $b);
}