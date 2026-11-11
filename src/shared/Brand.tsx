/** Original supplied artwork; icon.svg only frames the symbol, without redrawing it. */
export function Brand({ className = '' }: { className?: string }) {
  return (
    <span className={`brand-lockup ${className}`}>
      <img src="/brand/synera-mark.png" alt="" width="36" height="36" />
      <span>SYNERA</span>
    </span>
  );
}
