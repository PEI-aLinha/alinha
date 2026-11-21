/**
 * aLinha design system, ported from the MS3 high-fidelity prototype (frontend-prototype/src/ui).
 * Plugins import components from here; the shell imports `@alinha/ui/theme.css` once.
 * Usage notes for plugin authors: README.md in this package.
 */
export { cn } from './cn'
export { Button, type ButtonProps } from './Button'
export { buttonClass, type ButtonLook } from './buttonClass'
export { controlClass } from './controlClass'
export { Field, TextInput, TextArea, Select, type SelectOption } from './Field'
export { Checkbox, RadioGroup, type RadioOption } from './Choice'
export { Card, Eyebrow, StatCard, Note } from './Card'
export { Badge, RiskBadge, CriticalityBadge, AIBadge, levelClass, type BadgeTone, type Level } from './Badge'
export { Skeleton, SkeletonRows, EmptyState, ErrorState } from './States'
export { PageHeader } from './PageHeader'
export { Tabs, type TabDef } from './Tabs'
export { Modal, Drawer } from './Dialogs'
export { ToastProvider, useToast } from './Toast'
export { DataTable, type ColumnDef } from './DataTable'
export { Stepper, type Step } from './Stepper'
export { Logo, Avatar, initialsOf, type AvatarSize } from './Brand'
export { Segmented, type SegmentOption } from './Segmented'
