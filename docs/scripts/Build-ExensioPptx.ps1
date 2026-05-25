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
        Title = 'ExensioReload — Executive Briefing'
        Body  = @(
            'Leadership briefing on controlled, self-service semiconductor test-data resend across 20+ manufacturing sites',
            'Audience: engineering leadership, operations, and IT',
            'Goal: align on business impact, governance, and rollout'
        )
        Bullets = $false
    },
    @{
        Title = 'Why it matters'
        Body  = @(
            'Missing or late test-data files force teams to re-push lot and wafer payloads into downstream systems.',
            'Today this work is manual, opaque, and risky: SQL scripts, ticket handoffs, duplicate-send exposure, and site-specific playbooks.',
            'The result is wasted engineer time, slower yield decisions, and repeated operational fire drills.'
        )
    },
    @{
        Title = 'The answer'
        Body  = @(
            'ExensioReload standardizes discovery, staging, dispatch, and monitoring for test-data resends.',
            'One governed platform lets teams search metadata, stage only what is needed, and track every file to completion.',
            'Coverage is provable by site, sender, lot, wafer, and end-time date range.'
        )
    },
    @{
        Title = 'How teams use it'
        Body  = @(
            'Configure: choose environment, site, sender, and the right filters for lot, wafer, dates, tester, or data type.',
            'Preview: review matching files and get warnings for already-staged duplicates before dispatch.',
            'Monitor: follow live file status, session history, analytics, and exports in one place.'
        )
    },
    @{
        Title = 'Business impact'
        Body  = @(
            'Time to resolution drops from multi-day script cycles to self-service resend in minutes.',
            'Coverage clarity shows exactly which end-time ranges were resent, reducing overlap and gaps.',
            'One app supports 20+ sites instead of 20 different playbooks.',
            'Teams spend less time on manual recovery and more time on yield and throughput decisions.'
        )
    },
    @{
        Title = 'Governance and visibility'
        Body  = @(
            'Duplicate staging warnings appear before dispatch.',
            'Immutable staging ledger in SENDER_STAGE captures request IDs and timestamps.',
            'Dashboard and My Sessions provide live status, drill-down, charts, and export.',
            'Microsoft Entra SSO and role-based access keep control aligned with enterprise policy.'
        )
    },
    @{
        Title = 'Rollout path'
        Body  = @(
            'Phase 1 - Pilot: 2-3 sites with wizard and monitoring; success means fewer manual scripts and tickets.',
            'Phase 2 - Expand: onboard remaining sites and complete Entra role mapping.',
            'Phase 3 - Optimize: add coverage analytics and dashboard KPIs to reduce duplicate resends and MTTR.',
            'Decision needed: sponsor site onboarding, Entra group mapping, and operational ownership.'
        )
    },
    @{
        Title = 'Bottom line'
        Body  = @(
            'ExensioReload turns test-data resend from a tribal, site-by-site exercise into a governed, observable factory capability.',
            'Audit, roles, real-time status, and clear date coverage deliver production-grade rigor.',
            'The result is lower MTTR, fewer duplicates, and more confidence in downstream manufacturing data.'
        )
        Bullets = $false
    }
)

# --- Technical / project intelligence slides ---
$technicalSlides = @(
    @{
        Title = 'ExensioReload Technical Overview'
        Body  = @(
            'Fullstack application for semiconductor test data resend across 20+ manufacturing sites.',
            'Discover metadata from Oracle DBs, stage payloads, dispatch to sender queues, monitor completion.',
            'Modern glassmorphism UI built with Angular 21, Spring Boot 3, and Java 21.'
        )
        Bullets = $false
    },
    @{
        Title = 'Architecture summary'
        Body  = @(
            'Frontend: Angular 21.2 on port 4200 in dev, proxied to backend; Signals + RxJS; glass design system.',
            'Backend: Spring Boot on port 8004 with context /exensioreload; JWT + Entra OIDC; Liquibase; HikariCP per site.',
            'Data: Oracle RefDB for staging plus 20+ site Oracle DBs; H2 for dev and test.',
            'Active app location: frontend/ only; there is no separate new_frontend workspace.'
        )
    },
    @{
        Title = 'Backend tech stack'
        Body  = @(
            'Java 21, Spring Boot 4.0.6, Maven, and JPA/Hibernate.',
            'Oracle ojdbc 23.3, H2 for tests, Liquibase, and HikariCP 5.0.1.',
            'JWT (jjwt) plus Microsoft Entra OIDC, Caffeine cache, and Micrometer/Prometheus.',
            'Package root: com.onsemi.cim.apps.exensio.exensioreload, with roughly 148 Java source files.'
        )
    },
    @{
        Title = 'Key configuration'
        Body  = @(
            'server.port=8004 and context-path=/exensioreload.',
            'jwt.ttl=900s and refresh cookie max-age=604800 seconds (7 days).',
            'refdb.dispatch.interval-ms=60000.',
            'app.preview.fetch-cap and app.preview.max-rows-cap default to 2000; app.stage.max-rows-cap is 10000.',
            'external-db.allow-writes=false to gate external database writes.',
            'dbconnections.yml contains 20+ site Oracle connections for PROD and QA.'
        )
    },
    @{
        Title = 'REST API - Auth and senders'
        Body  = @(
            'Auth endpoints cover login, refresh, registration, verification, and password reset.',
            'Sender endpoints include discover/preview, preview-with-duplicates, historical-summary, stage-all, stage, and dispatch.',
            'Lookup endpoints expose senders, locations, dataTypes, testerTypes, and external metadata filters.',
            'Exports support preview CSV downloads and duplicate checks before staging.'
        )
    },
    @{
        Title = 'REST API - Stage, dashboard, admin'
        Body  = @(
            'Stage APIs provide records, stats, SSE monitor/{requestId}, CSV export, and active sessions.',
            'Dashboard APIs provide snapshot metrics plus per-site and per-sender records with CSV export.',
            'Admin APIs for SUPER_ADMIN support CRUD, role changes, status toggles, audit logs, and stats.',
            'Internal APIs expose pool stats, evict, metrics, and session management.',
            'Config APIs expose /api/config/limits for preview and stage thresholds.'
        )
    },
    @{
        Title = 'Database schema (Liquibase)'
        Body  = @(
            'SENDER_STAGE is the core staging table on RefDB and tracks status, request_id, and CP/Exensio keys.',
            'sender_queue, load_session, and load_session_payload support batch processing.',
            'users, user_roles, refresh tokens, verification tokens, password tokens, and audit_log support identity and audit.',
            'external_environment and external_location define environments.',
            'Liquibase manages 15 changelogs; JdbcExternalMetadataRepository contains Oracle-specific SQL.'
        )
    },
    @{
        Title = 'Authentication & authorization'
        Body  = @(
            'Local JWT plus refresh token storage in DB; HTTP-only refresh cookie survives browser restart.',
            'Entra OIDC activates when reloader.sso.enabled=true, with groups mapped to ADMIN and SUPER_ADMIN.',
            'JwtAuthenticationFilter runs in a stateless session model with CSRF disabled.',
            'Role hierarchy is SUPER_ADMIN, then ADMIN, then USER, enforced with PreAuthorize on controllers.',
            'SSO routes include /api/auth/sso/initiate, /api/auth/sso/silent, and callback /sso-callback.'
        )
    },
    @{
        Title = 'Background tasks'
        Body  = @(
            'SenderDispatchService runs every 60s and moves NEW items into the sender queue and then ENRICHMENT.',
            'SenderQueueMonitor runs every 10s for queue consumption and ES on/off routing.',
            'CpLogMonitor runs every 60s for ES polling and routes ENRICHMENT to EXENSIO_LOADING or FAILED.',
            'ExensioLoadMonitor runs every 60s and moves API load confirmation to DONE or FAILED.',
            'CompletionNotificationService runs every 5 minutes and emails users when a session completes.',
            'EtlSshTriggerService provides an optional SSH crontab kick via etlservers.yml.'
        )
    },
    @{
        Title = 'Status pipeline'
        Body  = @(
            'Active states flow from NEW to ENRICHMENT to EXENSIO_LOADING to DONE, FAILED, or CANCELLED.',
            'SenderDispatchService sends NEW directly to ENRICHMENT; ENQUEUED is dead code.',
            'StagePipelinePolicy routes after the CP queue: ES to CpLogMonitor, otherwise Exensio or DONE.',
            'Completion is capability-based and StatusMapper uses inExternalQueue for UI labels.',
            'See docs/STATUS_PIPELINE.md for full detail.'
        )
    },
    @{
        Title = 'Frontend - Angular 21'
        Body  = @(
            'Frontend uses 100% standalone components, lazy routes, and functional guards/interceptors.',
            'Signals manage UI state; RxJS powers HTTP and SSE; the design system is glassmorphism, not Material.',
            'Key routes include /exensioreload, /exensioreload/new, /my-sessions, and /admin/users.',
            'GlassDialogService handles dialogs, CVA form controls support form composition, and both dark and light themes are available.',
            'ECharts in MySessionsComponent provides trend and pie visualizations with ResizeObserver support.'
        )
    },
    @{
        Title = 'Core business flow'
        Body  = @(
            '1. Configure: environment, site, sender, and filters for lot, wafer, dates, tester, or data type.',
            '2. Preview: query external Oracle, compare duplicates against SENDER_STAGE, and stage selected rows or stage-all.',
            '   Adaptive sizing uses 1000 rows normally and 10000 plus bypassCap for historical or super-admin date ranges.',
            '3. Monitor: SSE progress, queue dispatch, and session-completion email.',
            'Dashboard polling runs every 10s and enables drill-down by site and sender.'
        )
    },
    @{
        Title = 'Key services & patterns'
        Body  = @(
            'BackendService centralizes API calls; AuthService handles JWT, refresh, and SSO restore.',
            'MonitoringService combines EventSource SSE with polling fallback.',
            'Immutable Java records back the DTOs, controllers stay thin, and scheduling uses @Scheduled plus @Async.',
            'Dynamic HikariCP pools are created per site and preview caching uses Caffeine with 200 entries and 30s TTL.',
            'Claim-based batch processing keeps payload claiming concurrency-safe.'
        )
    },
    @{
        Title = 'Development conventions'
        Body  = @(
            'Backend conventions favor records for DTOs, transactional services, audit IP/UserAgent capture, and JSON error handlers.',
            'Frontend conventions favor inject(), signal() for form state, a glass-* component prefix, and no NgModules.',
            'Use frontend/ with context /exensioreload/api and a dev proxy from :4200 to :8004.',
            'Oracle SQL uses ROWNUM and NVL; EXTERNAL_DB_ALLOW_WRITES gates writes.',
            'Standards remain Signals-first UI state with glass plus dark/light themes preserved.'
        )
    },
    @{
        Title = 'Critical gotchas'
        Body  = @(
            'StepperComponent is ~2098 LOC, SenderController ~1644, and RefDbService ~2334.',
            'AuthGuard restores the session asynchronously via refresh cookie on a new tab.',
            'Prefer GlassDialogService over Material Dialog for new work.',
            'CP ES and Exensio integrations are safe to deploy unconfigured because they no-op when URLs are disabled.',
            'There is no ES client library; raw HTTP uses java.net.http.HttpClient.',
            'The app header exposes /exensio-integration-hub/.'
        )
    },
    @{
        Title = 'Related documentation'
        Body  = @(
            'docs/EXENSIORELOAD.md - project intelligence and architecture notes.',
            'docs/INTEGRATION_ES_EXENSIO.md - CP Elasticsearch and Exensio API enablement.',
            'docs/ETL_SSH_TRIGGER.md - optional SSH ETL trigger.',
            'docs/SSO_ONBOARDING_DETAILS.md - Entra registration details.',
            'docs/STATUS_PIPELINE.md and RXJS_VS_SIGNALS_GUIDE.md.'
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
