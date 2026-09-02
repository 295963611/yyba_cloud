/** 品牌 Logo 静态资源路径（与 public/images/logo 对应） */
export const LOGO = {
  sm: '/images/logo/48x48.png',
  md: '/images/logo/72x72.png',
  lg: '/images/logo/96x96.png',
  xl: '/images/logo/144x144.png',
  xxl: '/images/logo/192x192.png'
}

export function logoUrl(size = 'lg') {
  return LOGO[size] || LOGO.lg
}
