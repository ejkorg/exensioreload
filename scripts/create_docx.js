const { Document, Packer, Paragraph, TextRun, HeadingLevel, Table, TableRow, TableCell, WidthType, AlignmentType, BorderStyle, ShadingType, LevelFormat } = require('docx');
const fs = require('fs');

// Read the markdown content
const mdContent = fs.readFileSync('AI_POWERED_DATA_INTEGRATION_SUPPORT_PROPOSAL.md', 'utf8');

// Create document sections
const children = [];

// Helper function to parse markdown and create docx elements
function parseMarkdownToDocx(md) {
  const lines = md.split('\n');
  let i = 0;
  
  while (i < lines.length) {
    const line = lines[i];
    
    // Headings
    if (line.startsWith('# ')) {
      children.push(new Paragraph({
        text: line.substring(2),
        heading: HeadingLevel.HEADING_1,
        spacing: { before: 400, after: 200 }
      }));
    } else if (line.startsWith('## ')) {
      children.push(new Paragraph({
        text: line.substring(3),
        heading: HeadingLevel.HEADING_2,
        spacing: { before: 300, after: 150 }
      }));
    } else if (line.startsWith('### ')) {
      children.push(new Paragraph({
        text: line.substring(4),
        heading: HeadingLevel.HEADING_3,
        spacing: { before: 200, after: 100 }
      }));
    } else if (line.startsWith('#### ')) {
      children.push(new Paragraph({
        text: line.substring(5),
        heading: HeadingLevel.HEADING_4,
        spacing: { before: 150, after: 75 }
      }));
    }
    // Horizontal rule
    else if (line.startsWith('---')) {
      children.push(new Paragraph({
        text: '',
        border: { bottom: { style: BorderStyle.SINGLE, size: 1, color: '999999' } }
      }));
    }
    // Code blocks
    else if (line.startsWith('```')) {
      let codeLines = [];
      i++;
      while (i < lines.length && !lines[i].startsWith('```')) {
        codeLines.push(lines[i]);
        i++;
      }
      children.push(new Paragraph({
        children: [new TextRun({ text: codeLines.join('\n'), font: 'Courier New', size: 20 })],
        shading: { type: ShadingType.SOLID, color: 'F5F5F5' },
        spacing: { before: 100, after: 100 }
      }));
    }
    // Table
    else if (line.startsWith('|') && line.includes('|')) {
      const tableLines = [];
      while (i < lines.length && lines[i].startsWith('|')) {
        tableLines.push(lines[i]);
        i++;
      }
      i--; // Step back since we went one too far
      
      // Parse table
      const rows = tableLines
        .filter(l => !l.match(/^\|[\s-|]+\|$/)) // Remove separator rows
        .map(l => l.split('|').filter(c => c.trim()).map(c => c.trim()));
      
      if (rows.length > 0) {
        const tableRows = rows.map((row, idx) => {
          return new TableRow({
            children: row.map(cell => {
              return new TableCell({
                children: [new Paragraph({
                  children: [new TextRun({ 
                    text: cell, 
                    bold: idx === 0,
                    size: 20 
                  })],
                })],
                shading: idx === 0 ? { type: ShadingType.SOLID, color: 'E0E0E0' } : undefined,
              });
            })
          });
        });
        
        children.push(new Table({
          rows: tableRows,
          width: { size: 100, type: WidthType.PERCENTAGE }
        }));
      }
    }
    // Bullet points
    else if (line.startsWith('- ') || line.startsWith('* ')) {
      children.push(new Paragraph({
        text: line.substring(2),
        bullet: { level: 0 },
        spacing: { before: 50, after: 50 }
      }));
    }
    // Numbered list
    else if (line.match(/^\d+\.\s/)) {
      children.push(new Paragraph({
        text: line.replace(/^\d+\.\s/, ''),
        numbering: { reference: 'default-numbering', level: 0 },
        spacing: { before: 50, after: 50 }
      }));
    }
    // Regular paragraph
    else if (line.trim()) {
      children.push(new Paragraph({
        children: [new TextRun({ text: line, size: 22 })],
        spacing: { before: 100, after: 100 }
      }));
    }
    // Empty line
    else {
      children.push(new Paragraph({ text: '' }));
    }
    
    i++;
  }
}

parseMarkdownToDocx(mdContent);

// Create document
const doc = new Document({
  numbering: {
    config: [
      {
        reference: 'default-numbering',
        levels: [{
          level: 0,
          format: LevelFormat.DECIMAL,
          text: '%1.',
          alignment: AlignmentType.LEFT,
        }]
      }
    ]
  },
  sections: [{
    properties: {},
    children: children
  }]
});

// Save document
Packer.toBuffer(doc).then(buffer => {
  fs.writeFileSync('AI_POWERED_DATA_INTEGRATION_SUPPORT_PROPOSAL.docx', buffer);
  console.log('Word document created successfully!');
}).catch(err => {
  console.error('Error creating document:', err);
});
