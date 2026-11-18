<?php
// One shared MySQL connection (PDO) for the whole request.
// The password lives in config/config.php, which is in .gitignore.
declare(strict_types=1);

function config(): array
{
    static $config = null;
    if ($config === null) {
        $file = __DIR__ . '/../config/config.php';
        if (!file_exists($file)) {
            http_response_code(500);
            exit('{"ok":false,"error":"config/config.php is missing"}');
        }
        $config = require $file;
    }
    return $config;
}

function db(): PDO
{
    static $pdo = null;
    if ($pdo === null) {
        $c = config();
        $pdo = new PDO(
            "mysql:host={$c['db_host']};dbname={$c['db_name']};charset=utf8mb4",
            $c['db_user'],
            $c['db_pass'],
            [
                PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,       // SQL errors become exceptions
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,  // rows as ['column' => value]
                PDO::ATTR_EMULATE_PREPARES => false,               // real prepared statements (no SQL injection)
            ]
        );
    }
    return $pdo;
}

/** Runs a query with ? placeholders and returns all rows. */
function rows(string $sql, array $args = []): array
{
    $st = db()->prepare($sql);
    $st->execute($args);
    return $st->fetchAll();
}

/** Runs a query and returns the first row, or null. */
function row(string $sql, array $args = []): ?array
{
    $st = db()->prepare($sql);
    $st->execute($args);
    $r = $st->fetch();
    return $r === false ? null : $r;
}

/** Runs an INSERT / UPDATE / DELETE and returns how many rows it changed. */
function exec_sql(string $sql, array $args = []): int
{
    $st = db()->prepare($sql);
    $st->execute($args);
    return $st->rowCount();
}