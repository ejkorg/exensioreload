#Requires -Version 5.1
<#
.SYNOPSIS
  Builds EXECUTIVE_PRESENTATION.pptx and EXENSIORELOAD.pptx from structured slide content.
#>
param(
    [string]$OutputDir = (Join-Path $PSScriptRoot '..')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Set-Utf8File([string]$Path, [string]$Content) {
    $dir = Split-Path $Path -Parent
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Escape-Xml([string]$t) {
    if ([string]::IsNullOrEmpty($t)) { return '' }
    return [System.Security.SecurityElement]::Escape($t)
}

function Get-ParagraphXml([string[]]$Lines, [switch]$Bullet) {
    $sb = New-Object System.Text.StringBuilder
    foreach ($line in $Lines) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        [void]$sb.Append('<a:p>')
        if ($Bullet) {
            [void]$sb.Append('<a:pPr marL="342900" indent="-342900"><a:buChar char="•"/></a:pPr>')
        }
        [void]$sb.Append('<a:r><a:rPr lang="en-US" dirty="0"/><a:t>')
        [void]$sb.Append((Escape-Xml $line.Trim()))
        [void]$sb.Append('</a:t></a:r></a:p>')
    }
    return $sb.ToString()
}

function Get-ShapeXml([string]$name, [int]$id, [string]$placeholderType, [int]$x, [int]$y, [int]$cx, [int]$cy, [string]$paragraphsXml) {
    @"
<p:sp>
  <p:nvSpPr>
    <p:cNvPr id="$id" name="$name"/>
    <p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>
    <p:nvPr><p:ph type="$placeholderType"/></p:nvPr>
  </p:nvSpPr>
  <p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm></p:spPr>
  <p:txBody>
    <a:bodyPr/>
    <a:lstStyle/>
    $paragraphsXml
  </p:txBody>
</p:sp>
"@
}

function Get-SlideXml([string]$title, [string[]]$Body, [switch]$Bullets) {
    $titleXml = Get-ParagraphXml @($title)
    $bodyXml = if ($Body -and $Body.Count -gt 0) { Get-ParagraphXml $Body -Bullet:$Bullets } else { '<a:p/>' }
    $shape1 = Get-ShapeXml 'Title' 2 'title' 457200 274638 8229600 1143000 $titleXml
    $shape2 = Get-ShapeXml 'Content' 3 'body' 457200 1600200 8229600 4525963 $bodyXml
    @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
       xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
       xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
      <p:grpSpPr/>
      $shape1
      $shape2
    </p:spTree>
  </p:cSld>
  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sld>
"@
}

function New-PptxFromSlides([string]$outPath, [object[]]$Slides) {
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("exensio-pptx-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $temp -Force | Out-Null

    $slideCount = $Slides.Count
    $slideRels = @()
    $slideParts = @()

    for ($i = 0; $i -lt $slideCount; $i++) {
        $n = $i + 1
        $slideParts += "/ppt/slides/slide$n.xml"
        $slide = $Slides[$i]
        $useBullets = if ($slide.ContainsKey('Bullets')) { [bool]$slide.Bullets } else { $true }
        $xml = Get-SlideXml $slide.Title $slide.Body -Bullets:$useBullets
        $slidePath = Join-Path $temp "ppt\slides\slide$n.xml"
        New-Item -ItemType Directory -Path (Split-Path $slidePath) -Force | Out-Null
        [System.IO.File]::WriteAllText($slidePath, $xml, [System.Text.UTF8Encoding]::new($false))

        $relPath = Join-Path $temp "ppt\slides\_rels\slide$n.xml.rels"
        New-Item -ItemType Directory -Path (Split-Path $relPath) -Force | Out-Null
        @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout2.xml"/>
</Relationships>
"@ | ForEach-Object { Set-Utf8File $relPath $_ }
        $slideRels += "  <Relationship Id=`"rId$($n + 1)`" Type=`"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide`" Target=`"slides/slide$n.xml`"/>"
    }

    # Theme
    $themeDir = Join-Path $temp 'ppt\theme'
    New-Item -ItemType Directory -Path $themeDir -Force | Out-Null
    @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office Theme">
  <a:themeElements>
    <a:clrScheme name="Office">
      <a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>
      <a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
      <a:dk2><a:srgbClr val="1F497D"/></a:dk2>
      <a:lt2><a:srgbClr val="EEECE1"/></a:lt2>
      <a:accent1><a:srgbClr val="4F81BD"/></a:accent1>
      <a:accent2><a:srgbClr val="C0504D"/></a:accent2>
      <a:accent3><a:srgbClr val="9BBB59"/></a:accent3>
      <a:accent4><a:srgbClr val="8064A2"/></a:accent4>
      <a:accent5><a:srgbClr val="4BACC6"/></a:accent5>
      <a:accent6><a:srgbClr val="F79646"/></a:accent6>
      <a:hlink><a:srgbClr val="0000FF"/></a:hlink>
      <a:folHlink><a:srgbClr val="800080"/></a:folHlink>
    </a:clrScheme>
    <a:fontScheme name="Office">
      <a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>
      <a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont>
    </a:fontScheme>
    <a:fmtScheme name="Office">
      <a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>
      <a:lnStyleLst><a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="25400"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="38100"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>
      <a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>
      <a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst>
    </a:fmtScheme>
  </a:themeElements>
</a:theme>
'@ | ForEach-Object { Set-Utf8File (Join-Path $themeDir 'theme1.xml') $_ }

    # Slide master
    $masterDir = Join-Path $temp 'ppt\slideMasters'
    New-Item -ItemType Directory -Path $masterDir -Force | Out-Null
    @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
             xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
             xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld><p:bg><p:bgPr><a:solidFill><a:schemeClr val="bg1"/></a:solidFill></p:bgPr></p:bg><p:spTree>
    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
    <p:grpSpPr/>
  </p:spTree></p:cSld>
  <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
  <p:sldLayoutIdLst>
    <p:sldLayoutId id="2147483649" r:id="rId1"/>
    <p:sldLayoutId id="2147483650" r:id="rId2"/>
  </p:sldLayoutIdLst>
</p:sldMaster>
'@ | ForEach-Object { Set-Utf8File (Join-Path $masterDir 'slideMaster1.xml') $_ }

    New-Item -ItemType Directory -Path (Join-Path $masterDir '_rels') -Force | Out-Null
    @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout2.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
</Relationships>
'@ | ForEach-Object { Set-Utf8File (Join-Path $masterDir '_rels\slideMaster1.xml.rels') $_ }

    # Slide layouts
    $layoutDir = Join-Path $temp 'ppt\slideLayouts'
    New-Item -ItemType Directory -Path $layoutDir -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $layoutDir '_rels') -Force | Out-Null

    $layoutTemplate = @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
             xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
             xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="{0}">
  <p:cSld name="{1}"><p:spTree>
    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
    <p:grpSpPr/>
    {2}
  </p:spTree></p:cSld>
  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sldLayout>
'@

    $titlePh = @'
<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="457200" y="274638"/><a:ext cx="8229600" cy="1143000"/></a:xfrm></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody></p:sp>
'@
    $bodyPh = @'
<p:sp><p:nvSpPr><p:cNvPr id="3" name="Content"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr>
<p:spPr><a:xfrm><a:off x="457200" y="1600200"/><a:ext cx="8229600" cy="4525963"/></a:xfrm></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody></p:sp>
'@

    Set-Utf8File (Join-Path $layoutDir 'slideLayout1.xml') ($layoutTemplate -f 'title', 'Title Slide', $titlePh)
    Set-Utf8File (Join-Path $layoutDir 'slideLayout2.xml') ($layoutTemplate -f 'obj', 'Title and Content', ($titlePh + $bodyPh))

    Set-Utf8File (Join-Path $layoutDir '_rels\slideLayout1.xml.rels') @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
</Relationships>
'@
    Set-Utf8File (Join-Path $layoutDir '_rels\slideLayout2.xml.rels') @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
</Relationships>
'@

    # Presentation
    $sldIdLst = (1..$slideCount | ForEach-Object {
        $id = 256 + $_
        "    <p:sldId id=`"$id`" r:id=`"rId$($_ + 1)`"/>"
    }) -join "`n"

    New-Item -ItemType Directory -Path (Join-Path $temp 'ppt\_rels') -Force | Out-Null
    @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" saveSubsetFonts="1">
  <p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
  <p:sldIdLst>
$sldIdLst
  </p:sldIdLst>
  <p:sldSz cx="9144000" cy="6858000" type="screen4x3"/>
  <p:notesSz cx="6858000" cy="9144000"/>
  <p:defaultTextStyle/>
</p:presentation>
"@ | ForEach-Object { Set-Utf8File (Join-Path $temp 'ppt\presentation.xml') $_ }

    $presRels = @(
        '  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>'
    ) + $slideRels
    @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
$($presRels -join "`n")
</Relationships>
"@ | ForEach-Object { Set-Utf8File (Join-Path $temp 'ppt\_rels\presentation.xml.rels') $_ }

    Set-Utf8File (Join-Path $temp 'ppt\presProps.xml') @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presProps xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"/>
'@
    Set-Utf8File (Join-Path $temp 'ppt\viewProps.xml') @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:viewPr xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:normalViewPr><p:restoredLeft sz="15620"/><p:restoredTop sz="94660"/></p:normalViewPr><p:slideViewPr/></p:viewPr>
'@
    Set-Utf8File (Join-Path $temp 'ppt\tableStyles.xml') @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:tblStyleLst xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" def="{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}"/>
'@

    # Doc props
    New-Item -ItemType Directory -Path (Join-Path $temp 'docProps') -Force | Out-Null
    $now = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ssZ')
    $baseName = [System.IO.Path]::GetFileNameWithoutExtension($outPath)
    @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
  xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <dc:title>$baseName</dc:title>
  <dc:creator>ExensioReload</dc:creator>
  <dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created>
  <dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified>
</cp:coreProperties>
"@ | ForEach-Object { Set-Utf8File (Join-Path $temp 'docProps\core.xml') $_ }
    Set-Utf8File (Join-Path $temp 'docProps\app.xml') @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
  <Application>Microsoft PowerPoint</Application>
  <Slides>$slideCount</Slides>
</Properties>
"@

    # Root rels
    New-Item -ItemType Directory -Path (Join-Path $temp '_rels') -Force | Out-Null
    @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" Target="docProps/core.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>
'@ | ForEach-Object { Set-Utf8File (Join-Path $temp '_rels\.rels') $_ }

    # Content types
    $overrides = @(
        '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>',
        '<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>',
        '<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>',
        '<Override PartName="/ppt/slideLayouts/slideLayout2.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>',
        '<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>',
        '<Override PartName="/ppt/presProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presProps+xml"/>',
        '<Override PartName="/ppt/viewProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.viewProps+xml"/>',
        '<Override PartName="/ppt/tableStyles.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.tableStyles+xml"/>',
        '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>',
        '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>'
    )
    foreach ($part in $slideParts) {
        $overrides += "<Override PartName=`"$part`" ContentType=`"application/vnd.openxmlformats-officedocument.presentationml.slide+xml`"/>"
    }
    @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
$($overrides -join "`n")
</Types>
"@ | ForEach-Object { Set-Utf8File (Join-Path $temp '[Content_Types].xml') $_ }

    if (Test-Path $outPath) { Remove-Item $outPath -Force }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($temp, $outPath)
    Remove-Item $temp -Recurse -Force
}

# --- Executive presentation slides ---
$executiveSlides = @(
    @{
        Title = 'ExensioReload - Executive Overview'
        Body  = @(
            'Controlled, self-service resend of semiconductor test data across 20+ manufacturing sites',
            'Audience: Engineering leadership, operations, IT',
            'Version 1.0 - onsemi internal'
        )
        Bullets = $false
    },
    @{
        Title = 'The challenge'
        Body  = @(
            'Test data gaps and late-arriving files force teams to re-push lot/wafer payloads into downstream systems.',
            'Today: manual SQL/scripts, opaque status, duplicate-send risk, slow site-specific playbooks.',
            'Cost: engineer time, delayed yield decisions, repeated fire drills.'
        )
    },
    @{
        Title = 'The solution'
        Body  = @(
            'ExensioReload standardizes discover, stage, dispatch, and monitor for test-data resends.',
            'One platform: search metadata, stage only what is needed, track every file to completion.',
            'Prove coverage by site, sender, lot, and end-time date range.'
        )
    },
    @{
        Title = 'How it works - 3 steps'
        Body  = @(
            'Configure: Pick environment, site, sender; filter by lot, wafer, date range, tester/data type.',
            'Preview: See matching files; warnings for already-staged duplicates.',
            'Monitor: Live status per file; session history, analytics, and export.'
        )
    },
    @{
        Title = 'End-to-end data pipeline'
        Body  = @(
            'NEW - staged from discovery',
            'ENRICHMENT - dispatched to sender queue; CP success when ES enabled',
            'EXENSIO_LOADING - Exensio confirms load (optional)',
            'DONE / FAILED / CANCELLED - background services poll; single UI status; email on session complete'
        )
    },
    @{
        Title = 'Operational impact'
        Body  = @(
            'Time to resolution: self-service resend in minutes, not multi-day script cycles.',
            'Coverage clarity: session end-time coverage shows exactly which dates were resent.',
            'Scale: one app for 20+ sites (PROD/QA), not 20 different playbooks.',
            'Governance: role-based access, audit logs, duplicate detection.'
        )
    },
    @{
        Title = 'Risk reduction'
        Body  = @(
            'Duplicate staging warnings before dispatch.',
            'Immutable staging ledger (SENDER_STAGE) with request IDs and timestamps.',
            'Controlled writes to external DBs (gated by configuration).',
            'Enterprise SSO (Microsoft Entra OIDC) with group-to-role mapping.'
        )
    },
    @{
        Title = 'Visibility'
        Body  = @(
            'Dashboard: staged / ready / in-flight / done / failed across sites (auto-refresh).',
            'My Sessions: drill-down, charts, CSV export, file-level end times.',
            'Coverage reporting: cross-session view by data end-time (day/week/month).'
        )
    },
    @{
        Title = 'Technology at a glance'
        Body  = @(
            'Frontend: Angular 21, standalone components - maintainable UX, real-time monitoring.',
            'Backend: Spring Boot 3, Java 21 - schedulers, security, standard stack.',
            'Data: Oracle RefDB + per-site Oracle pools - fits manufacturing data estate.',
            'Ops: Prometheus metrics, pool diagnostics, email alerts.'
        )
    },
    @{
        Title = 'Who uses it'
        Body  = @(
            'Test / yield engineer: resend missing CP or Exensio loads for lot/wafer/date window.',
            'Site operations: track open sessions and confirm completion.',
            'Platform / admin: user provisioning, SSO groups, environment configuration.',
            'Leadership: dashboard health and session outcomes.'
        )
    },
    @{
        Title = 'Deployment & security'
        Body  = @(
            'Deployment: on-prem / internal enterprise (context path /exensioreload).',
            'Authentication: local JWT + optional Microsoft Entra SSO (7-day refresh cookie).',
            'Authorization: SUPER_ADMIN, ADMIN, USER with method-level security.',
            'Audit: user actions, IP, and user-agent on sensitive operations.'
        )
    },
    @{
        Title = 'Suggested rollout'
        Body  = @(
            'Phase 1 - Pilot: 2-3 sites, wizard + monitoring; success = fewer manual scripts/tickets.',
            'Phase 2 - Expand: remaining sites, dbconnections.yml, Entra role mapping.',
            'Phase 3 - Optimize: coverage analytics and dashboard KPIs; fewer duplicate resends, faster MTTR.'
        )
    },
    @{
        Title = 'Bottom line'
        Body  = @(
            'ExensioReload turns test-data resend from a tribal, site-by-site exercise into a governed, observable factory capability.',
            'Audit, roles, real-time status, and clear date coverage - production-grade rigor.',
            'Ask: Sponsor site onboarding, Entra group mapping, and operational ownership.'
        )
        Bullets = $false
    },
    @{
        Title = 'Appendix - One-page cheat sheet'
        Body  = @(
            'PROBLEM: Manual resends, duplicates, no coverage view',
            'SOLUTION: 3-step wizard + staging ledger + live monitoring',
            'USERS: Engineers, ops, admins',
            'INTEGRATIONS: Site Oracle, sender queues, CP/ES, Exensio (opt.)',
            'SECURITY: Entra SSO, RBAC, audit trail',
            'WIN: Faster MTTR, fewer duplicates, provable coverage',
            'Docs: EXENSIORELOAD.md, INTEGRATION_ES_EXENSIO.md, ETL_SSH_TRIGGER.md, SSO_ONBOARDING_DETAILS.md'
        )
    }
)

# --- Technical / project intelligence slides ---
$technicalSlides = @(
    @{
        Title = 'ExensioReload - Project Intelligence'
        Body  = @(
            'Fullstack application for semiconductor test data resend across 20+ manufacturing sites.',
            'Discover metadata from Oracle DBs, stage payloads, dispatch to sender queues, monitor completion.',
            'Modern glassmorphism UI - Angular 21 + Spring Boot 3 / Java 21'
        )
        Bullets = $false
    },
    @{
        Title = 'Architecture summary'
        Body  = @(
            'Frontend: Angular 21.2, port 4200 (dev) proxied to backend; Signals + RxJS; glass design system.',
            'Backend: Spring Boot, port 8004, context /exensioreload; JWT + Entra OIDC; Liquibase; HikariCP per site.',
            'Data: Oracle RefDB (staging) + 20+ site Oracle DBs; H2 for dev/test.',
            'Active app: frontend/ only (no separate new_frontend workspace).'
        )
    },
    @{
        Title = 'Backend tech stack'
        Body  = @(
            'Java 21, Spring Boot 4.0.6, Maven, JPA/Hibernate',
            'Oracle ojdbc 23.3, H2 (test), Liquibase, HikariCP 5.0.1',
            'JWT (jjwt) + Microsoft Entra OIDC, Caffeine cache, Micrometer/Prometheus',
            'Package: com.onsemi.cim.apps.exensio.exensioreload (~148 Java source files)'
        )
    },
    @{
        Title = 'Key configuration'
        Body  = @(
            'server.port=8004, context-path=/exensioreload',
            'jwt.ttl=900s, refresh cookie max-age=604800 (7 days)',
            'refdb.dispatch.interval-ms=60000',
            'app.preview.fetch-cap / max-rows-cap: 2000 default; app.stage.max-rows-cap: 10000',
            'external-db.allow-writes=false (gate for external DB writes)',
            'dbconnections.yml: 20+ site Oracle connections (PROD/QA)'
        )
    },
    @{
        Title = 'REST API - Auth and senders'
        Body  = @(
            'Auth (public): login, refresh, register, verify, password reset.',
            'Senders: discover/preview, preview-with-duplicates, historical-summary, stage-all, stage, dispatch.',
            'Lookups: senders, locations, dataTypes, testerTypes, external metadata filters.',
            'Exports: preview CSV; duplicate checks before staging.'
        )
    },
    @{
        Title = 'REST API - Stage, dashboard, admin'
        Body  = @(
            'Stage: records, stats, SSE monitor/{requestId}, CSV export, active-sessions.',
            'Dashboard: snapshot, per-site/sender records and CSV export.',
            'Admin users (SUPER_ADMIN): CRUD, roles, toggle status, audit-logs, stats.',
            'Internal: pool stats/evict, metrics, session management.',
            'Config: /api/config/limits for preview/stage thresholds.'
        )
    },
    @{
        Title = 'Database schema (Liquibase)'
        Body  = @(
            'SENDER_STAGE - core staging on RefDB (status, request_id, CP/Exensio keys).',
            'sender_queue, load_session, load_session_payload - batch processing.',
            'users, user_roles, refresh/verification/password tokens, audit_log.',
            'external_environment, external_location - environment definitions.',
            '15 changelogs; Oracle-specific SQL in JdbcExternalMetadataRepository.'
        )
    },
    @{
        Title = 'Authentication & authorization'
        Body  = @(
            'Local JWT + refresh token in DB; HTTP-only refresh cookie survives browser restart.',
            'Entra OIDC when reloader.sso.enabled=true; groups map to ADMIN and SUPER_ADMIN.',
            'JwtAuthenticationFilter, stateless sessions, CSRF disabled.',
            'Role hierarchy: SUPER_ADMIN, then ADMIN, then USER; PreAuthorize on controllers.',
            'SSO: /api/auth/sso/initiate, /api/auth/sso/silent, callback /sso-callback.'
        )
    },
    @{
        Title = 'Background tasks'
        Body  = @(
            'SenderDispatchService 60s: NEW to DTP_SENDER_QUEUE_ITEM to ENRICHMENT.',
            'SenderQueueMonitor 10s: queue consumption; ES on/off routing.',
            'CpLogMonitor 60s: ES polling when configured; ENRICHMENT to EXENSIO_LOADING or FAILED.',
            'ExensioLoadMonitor 60s: API load confirm to DONE or FAILED.',
            'CompletionNotificationService every 5 min: email when session complete.',
            'EtlSshTriggerService: optional SSH crontab kick (etlservers.yml).'
        )
    },
    @{
        Title = 'Status pipeline'
        Body  = @(
            'Active: NEW to ENRICHMENT to EXENSIO_LOADING to DONE, FAILED, or CANCELLED.',
            'SenderDispatchService: NEW to ENRICHMENT directly (ENQUEUED is dead code).',
            'StagePipelinePolicy: after CP queue - ES to CpLogMonitor; else Exensio or DONE.',
            'Capability-based completion; StatusMapper uses inExternalQueue for UI labels.',
            'See docs/STATUS_PIPELINE.md for full detail.'
        )
    },
    @{
        Title = 'Frontend - Angular 21'
        Body  = @(
            '100% standalone components, lazy routes, functional guards/interceptors.',
            'Signals for UI state; RxJS for HTTP/SSE; glassmorphism design (not Material-themed).',
            'Key routes: /exensioreload (dashboard), /exensioreload/new (stepper), /my-sessions, /admin/users.',
            'GlassDialogService for dialogs; CVA form controls; dark + light themes.',
            'Charts: ECharts in MySessionsComponent (trend + pie, ResizeObserver).'
        )
    },
    @{
        Title = 'Core business flow - 3-step stepper'
        Body  = @(
            '1. Configure: environment, site, sender; filters (lot, wafer, dates, tester, data type).',
            '2. Preview: query external Oracle; duplicates vs SENDER_STAGE; stage selected or stage-all.',
            '   Adaptive sizing: 1000 rows normal; 10000 + bypassCap for historical/super-admin date range.',
            '3. Monitor: SSE progress; dispatch to queues; email on session completion.',
            'Dashboard: 10s polling snapshot; drill-down by site and sender.'
        )
    },
    @{
        Title = 'Key services & patterns'
        Body  = @(
            'BackendService (598 LOC): all API calls; AuthService: JWT + refresh + SSO restore.',
            'MonitoringService: EventSource SSE + polling fallback.',
            'Immutable Java records for DTOs; thin controllers; @Scheduled + @Async.',
            'Dynamic HikariCP pools per site; Caffeine preview cache (200 entries, 30s TTL).',
            'Claim-based batch processing for concurrent-safe payload claiming.'
        )
    },
    @{
        Title = 'Development conventions'
        Body  = @(
            'Backend: records for DTOs, transactional services, audit IP/UserAgent, JSON error handlers.',
            'Frontend: inject(), signal() for form state, glass-* component prefix, no NgModules.',
            'Use frontend/, context /exensioreload/api, dev proxy :4200 to :8004.',
            'Oracle SQL (ROWNUM, NVL); EXTERNAL_DB_ALLOW_WRITES for writes.',
            'Standards: Signals-first UI state; preserve glass + dark/light themes.'
        )
    },
    @{
        Title = 'Critical gotchas'
        Body  = @(
            'StepperComponent ~2098 LOC; SenderController ~1644; RefDbService ~2334.',
            'AuthGuard async: restoreSession via refresh cookie on new tab.',
            'GlassDialogService vs Material Dialog - prefer Glass for new work.',
            'CP ES + Exensio: safe to deploy unconfigured (no-op when URLs disabled).',
            'No ES client library - raw HTTP via java.net.http.HttpClient.',
            'Hub link: /exensio-integration-hub/ in app header.'
        )
    },
    @{
        Title = 'Related documentation'
        Body  = @(
            'docs/EXENSIORELOAD.md - this intelligence doc',
            'docs/INTEGRATION_ES_EXENSIO.md - CP Elasticsearch and Exensio API',
            'docs/ETL_SSH_TRIGGER.md - optional SSH ETL trigger',
            'docs/SSO_ONBOARDING_DETAILS.md - Entra registration',
            'docs/STATUS_PIPELINE.md, RXJS_VS_SIGNALS_GUIDE.md'
        )
        Bullets = $false
    }
)

$outDir = (Resolve-Path $OutputDir).Path
New-PptxFromSlides (Join-Path $outDir 'EXECUTIVE_PRESENTATION.pptx') $executiveSlides
New-PptxFromSlides (Join-Path $outDir 'EXENSIORELOAD.pptx') $technicalSlides

Write-Host "Created:"
Write-Host ('  ' + (Join-Path $outDir 'EXECUTIVE_PRESENTATION.pptx') + ' (' + $executiveSlides.Count + ' slides)')
Write-Host ('  ' + (Join-Path $outDir 'EXENSIORELOAD.pptx') + ' (' + $technicalSlides.Count + ' slides)')
