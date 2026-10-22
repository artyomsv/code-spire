import type { LandSettings } from './landSettingsApi';

interface Props {
  repositoryId: string;
  settings: LandSettings;
  open: string | null;
  setOpen: (open: string | null) => void;
  changed: (notice?: string) => void;
  reload: () => void;
}

/** RED placeholder for the Land step. */
export default function LandStep(props: Props) {
  void props;
  return null;
}
