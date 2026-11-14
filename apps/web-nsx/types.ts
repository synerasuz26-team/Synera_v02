export type Screen =
  | 'overview'
  | 'organization'
  | 'routes'
  | 'catalog'
  | 'orders'
  | 'finance'
  | 'tickets'
  | 'reports'
  | 'settings'
  | 'stores';

export type Role =
  | 'leader'
  | 'rsm'
  | 'asm'
  | 'supervisor'
  | 'sales'
  | 'intake'
  | 'support'
  | 'trade'
  | 'accounting'
  | 'admin';

export type ToastMessage = { message: string; undo?: () => void };
