// This is the prototype's decorative demo QR, not a payment credential.
const rows = [
  '1111111010101111111','1000001001011000001','1011101010101011101',
  '1011101001011011101','1011101010001011101','1000001001011000001',
  '1111111010101111111','0000000011000000000','1011011101011010110',
  '0100100110100101001','1101101001010011010','0000000010110100101',
  '1111111001001011001','1000001010100100100','1011101001011101011',
  '1011101010100010100','1011101001011001011','1000001010000100100',
  '1111111001011011011',
]

export default function QrCode() {
  return <svg viewBox="0 0 152 152" width={152} height={152} shapeRendering="crispEdges" role="img" aria-label="Demo loyalty QR code">
    {rows.flatMap((row, y) => [...row].map((cell, x) => cell === '1' ? <rect key={`${x}-${y}`} x={x * 8} y={y * 8} width={8} height={8} fill="#1C1C1E" /> : null))}
  </svg>
}
