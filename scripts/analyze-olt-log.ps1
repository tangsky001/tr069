[Console]::OutputEncoding = [Text.Encoding]::UTF8
$log = 'c:/Users/Administrator/Desktop/tr069-acs-master/tr069/target/log/ACS_LOG.LOG'
$lines = (Select-String -Path $log -Pattern 'OLT 命令耗时').Line
$rows = foreach ($l in $lines) {
    if ($l -match 'OLT 命令耗时 \[([^\]]+)\] 首字节 ([^/]+?) / (完成|失败于) (\d+)ms') {
        [pscustomobject]@{ cmd = $Matches[1]; fb = $Matches[2].Trim(); kind = $Matches[3]; total = [int]$Matches[4] }
    }
}
"总命令数: $($rows.Count)"
"---- 最慢 30 条 ----"
$rows | Sort-Object total -Descending | Select-Object -First 30 |
    ForEach-Object { "{0,8} ms  首字节={1,-12} {2}" -f $_.total, $_.fb, $_.cmd }
"---- GBK 重试 ----"
(Select-String -Path $log -Pattern 'GBK 重试').Line | Select-Object -Last 5
"---- 命令耗时总和（按命令聚合，前 15）----"
$rows | Group-Object cmd | ForEach-Object {
    [pscustomobject]@{ cmd = $_.Name; n = $_.Count; sum = ($_.Group | Measure-Object total -Sum).Sum }
} | Sort-Object sum -Descending | Select-Object -First 15 |
    ForEach-Object { "{0,8} ms  x{1,-4} {2}" -f $_.sum, $_.n, $_.cmd }
