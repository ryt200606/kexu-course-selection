import { api, query, type Page, type Row } from './api'

export function findRecords(
  path: string,
  keyword: string,
  page = 0,
  request: (url: string) => Promise<Page<Row>> = api
) {
  const separator = path.includes('?') ? '&' : '?'
  return request(path + separator + query({ q: keyword.trim(), size: 20, page }).slice(1))
}
