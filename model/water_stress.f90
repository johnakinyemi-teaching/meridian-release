module water_stress
    implicit none
    private
    public :: calculate_stress

contains

    real function calculate_stress(sw, awc, rdepth)
        real, intent(in) :: sw, awc, rdepth
        real :: capacity

        capacity = awc * rdepth
        if (capacity <= 0.0) capacity = 1.0

        calculate_stress = min(sw / capacity, 1.0)

        if (calculate_stress > 0.5) then
            calculate_stress = 1.0
        else
            calculate_stress = calculate_stress / 0.5
        end if
    end function calculate_stress

end module water_stress
