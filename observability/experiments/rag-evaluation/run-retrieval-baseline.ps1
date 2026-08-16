[CmdletBinding()]
param(
  [string]$BaseUrl = 'http://localhost:8080',
  [int[]]$KnowledgeBaseIds = @(2, 3, 4, 5, 6, 7),
  [switch]$DisableRewrite,
  [ValidateSet('VECTOR', 'HYBRID', 'HYBRID_RERANK')]
  [string]$RetrievalMode = 'VECTOR',
  [string]$DatasetPath = '',
  [string]$OutputDirectory = ''
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($DatasetPath)) {
  $DatasetPath = Join-Path $PSScriptRoot 'rehevo-gold-v1.jsonl'
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
  $OutputDirectory = Join-Path $PSScriptRoot 'runs'
}

if (-not (Test-Path -LiteralPath $DatasetPath)) {
  throw "未找到评测集：$DatasetPath"
}

$cases = Get-Content -LiteralPath $DatasetPath -Encoding UTF8 |
  Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
  ForEach-Object { $_ | ConvertFrom-Json }

if ($cases.Count -eq 0) {
  throw '评测集为空'
}

$requests = @($cases | ForEach-Object {
  @{ knowledgeBaseIds = @($KnowledgeBaseIds); question = $_.question }
})
$body = @{
  queries = $requests
  rewrite = (-not $DisableRewrite)
  retrievalMode = $RetrievalMode
} | ConvertTo-Json -Depth 6
$httpParameters = @{
  Uri = "$BaseUrl/api/knowledgebase/evaluation/retrieval"
  Method = 'Post'
  ContentType = 'application/json; charset=utf-8'
  Body = [System.Text.Encoding]::UTF8.GetBytes($body)
  TimeoutSec = 600
}
$requestStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$response = Invoke-RestMethod @httpParameters
$requestStopwatch.Stop()

if (-not $response.success -or $null -eq $response.data.items) {
  throw "评测请求失败：$($response | ConvertTo-Json -Depth 5 -Compress)"
}
if ($response.data.items.Count -ne $cases.Count) {
  throw "响应数量不一致：期望 $($cases.Count)，实际 $($response.data.items.Count)"
}

$perCase = for ($index = 0; $index -lt $cases.Count; $index++) {
  $case = $cases[$index]
  $item = $response.data.items[$index]
  $expected = @($case.expectedChunkRefs | ForEach-Object {
    "$($_.documentSha256):$($_.chunkIndex)"
  })
  $evidence = @($item.evidence)
  $ranks = @()
  for ($rank = 0; $rank -lt $evidence.Count; $rank++) {
    $actual = "$($evidence[$rank].documentSha256):$($evidence[$rank].chunkIndex)"
    if ($expected -contains $actual) {
      $ranks += ($rank + 1)
    }
  }

  $relevantAt5 = @($ranks | Where-Object { $_ -le 5 }).Count
  $recallAt5 = if ($expected.Count -gt 0) { $relevantAt5 / $expected.Count } else { $null }
  $mrrAt10 = if ($ranks.Count -gt 0 -and $ranks[0] -le 10) { 1.0 / $ranks[0] } else { 0.0 }
  $dcgAt10 = 0.0
  foreach ($rank in $ranks | Where-Object { $_ -le 10 }) {
    $dcgAt10 += 1.0 / [Math]::Log($rank + 1, 2)
  }
  $idealCount = [Math]::Min($expected.Count, 10)
  $idcgAt10 = 0.0
  for ($rank = 1; $rank -le $idealCount; $rank++) {
    $idcgAt10 += 1.0 / [Math]::Log($rank + 1, 2)
  }

  [pscustomobject]@{
    id = $case.id
    category = $case.category
    answerable = $case.answerable
    question = $case.question
    retrievalQuery = $item.retrievalQuery
    expectedChunkRefs = $case.expectedChunkRefs
    evidence = $item.evidence
    recallAt5 = $recallAt5
    mrrAt10 = if ($case.answerable) { $mrrAt10 } else { $null }
    ndcgAt10 = if ($case.answerable -and $idcgAt10 -gt 0) { $dcgAt10 / $idcgAt10 } else { $null }
  }
}

$answerable = @($perCase | Where-Object { $_.answerable })
$unanswerable = @($perCase | Where-Object { -not $_.answerable })
$summary = [pscustomobject]@{
  evaluatedAt = (Get-Date).ToString('o')
  gitCommit = (git -C (Join-Path $PSScriptRoot '..\..\..') rev-parse HEAD).Trim()
  knowledgeBaseIds = @($KnowledgeBaseIds)
  rewriteEnabled = (-not $DisableRewrite)
  retrievalMode = $RetrievalMode
  requestDurationMs = $requestStopwatch.Elapsed.TotalMilliseconds
  averageDurationPerQueryMs = $requestStopwatch.Elapsed.TotalMilliseconds / $cases.Count
  totalCases = $perCase.Count
  answerableCases = $answerable.Count
  unanswerableCases = $unanswerable.Count
  recallAt5 = ($answerable | Measure-Object -Property recallAt5 -Average).Average
  mrrAt10 = ($answerable | Measure-Object -Property mrrAt10 -Average).Average
  ndcgAt10 = ($answerable | Measure-Object -Property ndcgAt10 -Average).Average
  unanswerableEmptyEvidenceRate = ($unanswerable | Where-Object { @($_.evidence).Count -eq 0 }).Count / $unanswerable.Count
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$mode = "$($RetrievalMode.ToLower())-$(if ($DisableRewrite) { 'no-rewrite' } else { 'rewrite' })"
$runPath = Join-Path $OutputDirectory ("retrieval-baseline-$mode-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
[pscustomobject]@{
  summary = $summary
  cases = $perCase
} | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $runPath -Encoding UTF8

$summary | ConvertTo-Json -Depth 4
Write-Host "结果已写入：$runPath"
