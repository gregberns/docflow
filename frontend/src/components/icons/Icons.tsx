interface IconProps {
  className?: string;
  size?: number;
}

function svgProps(size: number, className?: string) {
  return {
    width: size,
    height: size,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeLinecap: "round" as const,
    "aria-hidden": true,
    className,
  };
}

export function ChevronRightIcon({ className, size = 16 }: IconProps) {
  return (
    <svg {...svgProps(size, className)} strokeWidth={2}>
      <polyline points="9 18 15 12 9 6" />
    </svg>
  );
}

export function ChevronLeftIcon({ className, size = 14 }: IconProps) {
  return (
    <svg {...svgProps(size, className)} strokeWidth={2}>
      <polyline points="15 18 9 12 15 6" />
    </svg>
  );
}

export function UploadIcon({ className, size = 14 }: IconProps) {
  return (
    <svg {...svgProps(size, className)} strokeWidth={2.5}>
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
      <polyline points="17 8 12 3 7 8" />
      <line x1="12" y1="3" x2="12" y2="15" />
    </svg>
  );
}

export function PlusIcon({ className, size = 14 }: IconProps) {
  return (
    <svg {...svgProps(size, className)} strokeWidth={2.5}>
      <line x1="12" y1="5" x2="12" y2="19" />
      <line x1="5" y1="12" x2="19" y2="12" />
    </svg>
  );
}

export function MinusIcon({ className, size = 14 }: IconProps) {
  return (
    <svg {...svgProps(size, className)} strokeWidth={2.5}>
      <line x1="5" y1="12" x2="19" y2="12" />
    </svg>
  );
}

export function FlagIcon({ className, size = 18 }: IconProps) {
  return (
    <svg {...svgProps(size, className)} strokeWidth={2.5}>
      <path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z" />
      <line x1="4" y1="22" x2="4" y2="15" />
    </svg>
  );
}

export function WarningIcon({ className, size = 18 }: IconProps) {
  return (
    <svg {...svgProps(size, className)} strokeWidth={2.5}>
      <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
      <line x1="12" y1="9" x2="12" y2="13" />
      <line x1="12" y1="17" x2="12.01" y2="17" />
    </svg>
  );
}

export function CheckIcon({ className, size = 14 }: IconProps) {
  return (
    <svg {...svgProps(size, className)} strokeWidth={2.5}>
      <polyline points="20 6 9 17 4 12" />
    </svg>
  );
}
